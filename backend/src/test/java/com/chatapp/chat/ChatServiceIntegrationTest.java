package com.chatapp.chat;

import com.chatapp.TestDatabase;
import com.chatapp.auth.AuthService;
import com.chatapp.auth.PasswordHasher;
import com.chatapp.auth.User;
import com.chatapp.chat.ChatModels.ConversationView;
import com.chatapp.chat.ChatModels.MessageView;
import com.chatapp.chat.ChatModels.SendResult;
import com.chatapp.common.AppException;
import com.chatapp.config.AppProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks message order, duplicates and concurrent sends against a real Postgres.
 * Run with: TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/chat_test mvn test
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ChatServiceIntegrationTest {
    private HikariDataSource ds;
    private ChatService chat;
    private User alice;
    private User bob;
    private User carol;

    @BeforeEach
    void setUp() {
        ds = TestDatabase.fresh();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(ds);
        AppProperties props = new AppProperties(false, Duration.ofDays(30), "", List.of());
        AuthService auth = new AuthService(jdbc, txManager, new PasswordHasher(1_000, 4), props);
        chat = new ChatService(jdbc, txManager);
        alice = auth.register("alice@example.com", "password-a", "Alice", null).user();
        bob = auth.register("bob@example.com", "password-b", "Bob", null).user();
        carol = auth.register("carol@example.com", "password-c", "Carol", null).user();
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    @Test
    void concurrentSendersGetAGapFreeStrictlyIncreasingSequence() throws Exception {
        long conv = chat.startConversation(alice, bob).id();
        int n = 200;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SendResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            User sender = i % 2 == 0 ? alice : bob;
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return chat.sendMessage(sender.id(), conv, UUID.randomUUID(), "msg " + idx);
            }));
        }
        start.countDown();
        Set<Long> seqs = ConcurrentHashMap.newKeySet();
        for (Future<SendResult> f : futures) seqs.add(f.get().message().seq());
        pool.shutdown();

        assertEquals(LongStream.rangeClosed(1, n).boxed().collect(Collectors.toSet()), seqs,
                "every seq 1..n used exactly once: no gaps, no duplicates");
    }

    @Test
    void retryWithSameClientMsgIdReturnsOriginalAndDoesNotBurnASeq() {
        long conv = chat.startConversation(alice, bob).id();
        UUID id = UUID.randomUUID();
        SendResult first = chat.sendMessage(alice.id(), conv, id, "hello");
        SendResult retry = chat.sendMessage(alice.id(), conv, id, "hello");
        SendResult next = chat.sendMessage(alice.id(), conv, UUID.randomUUID(), "second");

        assertFalse(first.duplicate());
        assertTrue(retry.duplicate());
        assertEquals(first.message().id(), retry.message().id());
        assertEquals(2, next.message().seq(), "the rolled-back retry must not leave a hole at seq 2");
    }

    @Test
    void concurrentRetriesOfTheSameMessageStoreItOnce() throws Exception {
        long conv = chat.startConversation(alice, bob).id();
        UUID id = UUID.randomUUID();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SendResult>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return chat.sendMessage(alice.id(), conv, id, "same");
            }));
        }
        start.countDown();
        int originals = 0;
        for (Future<SendResult> f : futures) if (!f.get().duplicate()) originals++;
        pool.shutdown();
        assertEquals(1, originals);
        assertEquals(2, chat.sendMessage(bob.id(), conv, UUID.randomUUID(), "next").message().seq());
    }

    @Test
    void simultaneousStartConversationCreatesExactlyOne() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ConversationView>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean aliceStarts = i % 2 == 0;
            futures.add(pool.submit(() -> {
                start.await();
                return aliceStarts ? chat.startConversation(alice, bob) : chat.startConversation(bob, alice);
            }));
        }
        start.countDown();
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        for (Future<ConversationView> f : futures) ids.add(f.get().id());
        pool.shutdown();
        assertEquals(1, ids.size());
    }

    @Test
    void outsidersCanNeitherReadNorWrite() {
        long conv = chat.startConversation(alice, bob).id();
        assertEquals(404, assertThrows(AppException.class,
                () -> chat.sendMessage(carol.id(), conv, UUID.randomUUID(), "let me in")).status());
        assertEquals(404, assertThrows(AppException.class,
                () -> chat.listMessages(carol.id(), conv, null, null, 10)).status());
    }

    @Test
    void historyPagesBackwardsAndCatchUpReadsForward() {
        long conv = chat.startConversation(alice, bob).id();
        for (int i = 1; i <= 7; i++) chat.sendMessage(alice.id(), conv, UUID.randomUUID(), "m" + i);

        assertEquals(List.of(5L, 6L, 7L), seqs(chat.listMessages(bob.id(), conv, null, null, 3)));
        assertEquals(List.of(2L, 3L, 4L), seqs(chat.listMessages(bob.id(), conv, null, 5L, 3)));
        assertEquals(List.of(6L, 7L), seqs(chat.listMessages(bob.id(), conv, 5L, null, 50)));
    }

    @Test
    void conversationListShowsPeerAndLatestMessageMostRecentFirst() throws Exception {
        long withBob = chat.startConversation(alice, bob).id();
        long withCarol = chat.startConversation(alice, carol).id();
        chat.sendMessage(bob.id(), withBob, UUID.randomUUID(), "from bob");
        Thread.sleep(5);
        chat.sendMessage(carol.id(), withCarol, UUID.randomUUID(), "from carol");

        List<ConversationView> list = chat.listConversations(alice.id());
        assertEquals(withCarol, list.get(0).id());
        assertEquals("carol@example.com", list.get(0).peer().email());
        assertEquals("from carol", list.get(0).lastMessage().body());
    }

    private static List<Long> seqs(List<MessageView> messages) {
        return messages.stream().map(MessageView::seq).toList();
    }
}
