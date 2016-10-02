/*******************************************************************************
 * Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 * materials are made available under the terms of the MIT License (MIT) which accompanies this
 * distribution, and is available at http://opensource.org/licenses/MIT
 *******************************************************************************/

package io.nats.client;

import static io.nats.client.Constants.ERR_BAD_SUBSCRIPTION;
import static io.nats.client.Constants.ERR_MAX_MESSAGES;
import static io.nats.client.Constants.ERR_SLOW_CONSUMER;
import static io.nats.client.UnitTestUtilities.runDefaultServer;
import static io.nats.client.UnitTestUtilities.sleep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Category(UnitTest.class)
public class ITSubscriptionTest {
    final Logger logger = LoggerFactory.getLogger(ITSubscriptionTest.class);

    ExecutorService executor =
            Executors.newCachedThreadPool(new NATSThreadFactory("nats-test-thread"));

    @Rule
    public TestCasePrinterRule pr = new TestCasePrinterRule(System.out);

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {}

    @AfterClass
    public static void tearDownAfterClass() throws Exception {}

    @Before
    public void setUp() throws Exception {}

    @After
    public void tearDown() throws Exception {}

    @Test
    public void testServerAutoUnsub() throws IOException, TimeoutException {
        try (NATSServer srv = runDefaultServer()) {
            try (Connection c = new ConnectionFactory().createConnection()) {
                assertFalse(c.isClosed());
                final AtomicLong received = new AtomicLong(0L);
                int max = 10;

                try (Subscription s = c.subscribe("foo", new MessageHandler() {
                    @Override
                    public void onMessage(Message msg) {
                        received.incrementAndGet();
                    }
                })) {
                    s.autoUnsubscribe(max);
                    int total = 100;

                    for (int i = 0; i < total; i++) {
                        c.publish("foo", "Hello".getBytes());
                    }
                    try {
                        c.flush();
                    } catch (Exception e1) {
                        fail(e1.getMessage());
                    }

                    sleep(100);

                    assertEquals(max, received.get());
                    assertFalse("Expected subscription to be invalid after hitting max",
                            s.isValid());
                }
            }
        }
    }

    @Test
    public void testClientSyncAutoUnsub() {
        try (NATSServer s = runDefaultServer()) {
            try (Connection c = new ConnectionFactory().createConnection()) {
                assertFalse(c.isClosed());

                long received = 0;
                int max = 10;
                boolean exThrown = false;
                try (SyncSubscription sub = c.subscribeSync("foo")) {
                    sub.autoUnsubscribe(max);

                    int total = 100;
                    for (int i = 0; i < total; i++) {
                        c.publish("foo", "Hello".getBytes());
                    }
                    try {
                        c.flush();
                    } catch (Exception e) {
                        fail(e.getMessage());
                    }

                    while (true) {
                        try {
                            sub.nextMessage(100);
                            received++;
                        } catch (IOException e) {
                            assertEquals(ERR_MAX_MESSAGES, e.getMessage());
                            exThrown = true;
                            break;
                        } catch (Exception e) {
                            // catch-all
                            fail("Wrong exception: " + e.getMessage());
                        }
                    }
                    assertTrue("Should have thrown IOException", exThrown);
                    assertEquals(max, received);
                    assertFalse("Expected subscription to be invalid after hitting max",
                            sub.isValid());
                }
            } catch (IOException | TimeoutException e2) {
                fail("Should have connected");
            }
        }
    }

    @Test
    public void testClientAsyncAutoUnsub() throws IOException, TimeoutException {
        final AtomicInteger received = new AtomicInteger(0);
        MessageHandler mh = new MessageHandler() {
            @Override
            public void onMessage(Message msg) {
                received.getAndIncrement();
            }
        };

        try (NATSServer srv = runDefaultServer()) {
            try (Connection c = new ConnectionFactory().createConnection()) {
                assertFalse(c.isClosed());

                int max = 10;
                try (Subscription s = c.subscribe("foo", mh)) {
                    s.autoUnsubscribe(max);

                    int total = 100;
                    for (int i = 0; i < total; i++) {
                        c.publish("foo", "Hello".getBytes());
                    }
                    try {
                        c.flush();
                    } catch (Exception e1) {
                        /* NOOP */
                    }

                    sleep(10);
                    assertFalse("Expected subscription to be invalid after hitting max",
                            s.isValid());
                    assertEquals(max, received.get());
                }
            }
        }
    }

    @Test
    public void testAutoUnsubAndReconnect() throws Exception {
        try (NATSServer srv = runDefaultServer()) {
            sleep(500);
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger received = new AtomicInteger(0);
            final int max = 10;

            ConnectionFactory cf = new ConnectionFactory();
            cf.setReconnectWait(50);
            cf.setReconnectAllowed(true);
            cf.setReconnectedCallback(new ReconnectedCallback() {
                public void onReconnect(ConnectionEvent event) {
                    latch.countDown();
                }
            });
            try (Connection nc = cf.createConnection()) {
                AsyncSubscriptionImpl sub =
                        (AsyncSubscriptionImpl) nc.subscribe("foo", new MessageHandler() {
                            public void onMessage(Message msg) {
                                received.incrementAndGet();
                            }
                        });
                sub.autoUnsubscribe(max);

                // Send less than the max
                int total = (int) max / 2;
                String str;
                for (int i = 0; i < total; i++) {
                    str = String.format("Hello %d", i + 1);
                    nc.publish("foo", str.getBytes());
                }
                nc.flush();

                // Restart the server
                srv.shutdown();

                try (NATSServer srv2 = runDefaultServer()) {
                    // and wait to reconnect
                    assertTrue("Failed to get the reconnect cb", latch.await(5, TimeUnit.SECONDS));
                    assertTrue("Subscription should still be valid", sub.isValid());
                    assertTrue(sub.isStarted());

                    // Ensure we only received 5 messages on our sub up to this point
                    assertEquals(total, nc.getStats().getInMsgs());

                    // Now send more than the total max.
                    int oldTotal = total;
                    total = 3 * max;
                    for (int i = 0; i < total; i++) {
                        str = String.format("Hello %d", i + oldTotal + 1);
                        nc.publish("foo", str.getBytes());
                    }
                    nc.flush();

                    // wait a bit before checking.
                    sleep(50, TimeUnit.MILLISECONDS);

                    assertEquals("Stats don't match reality, ", received.get(),
                            nc.getStats().getInMsgs());

                    // We should have received only up-to-max messages.
                    assertEquals("Received wrong total #msgs,", max, received.get());
                }
            }
        }
    }

    @Test
    public void testAutoUnsubWithParallelNextMsgCalls() {
        fail("not yet implemented");
    }

    @Test
    public void TestAutoUnsubscribeFromCallback() {
        fail("not yet implemented");
    }

    @Test
    public void testCloseSubRelease() throws IOException, TimeoutException {
        try (NATSServer srv = runDefaultServer()) {
            try (final Connection c = new ConnectionFactory().createConnection()) {
                try (SyncSubscription s = c.subscribeSync("foo")) {
                    long start = System.nanoTime();
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            sleep(5);
                            c.close();
                        }
                    });
                    boolean exThrown = false;
                    try {
                        sleep(5);
                        s.nextMessage(50, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        exThrown = true;
                    } finally {
                        assertTrue("Expected an error from nextMsg", exThrown);
                    }
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                    String msg = String.format("Too much time has elapsed to release NextMsg: %dms",
                            elapsed);
                    assertTrue(msg, elapsed <= 100);
                } catch (Exception e) {
                    fail("Subscription failed: " + e.getMessage());
                }
            }
        }
    }

    @Test
    public void testIsValidSubscriber() throws IOException, TimeoutException {
        try (NATSServer srv = runDefaultServer()) {

            try (final Connection nc = new ConnectionFactory().createConnection()) {
                try (SyncSubscription sub = nc.subscribeSync("foo")) {
                    assertTrue("Subscription should be valid", sub.isValid());

                    for (int i = 0; i < 10; i++) {
                        nc.publish("foo", "Hello".getBytes());
                    }
                    nc.flush();

                    try {
                        sub.nextMessage(200);
                    } catch (Exception e) {
                        fail("nextMsg threw an exception: " + e.getMessage());
                    }

                    sub.unsubscribe();


                    boolean exThrown = false;
                    try {
                        sub.autoUnsubscribe(1);
                    } catch (Exception e) {
                        assertTrue(e instanceof IllegalStateException);
                        assertEquals(ERR_BAD_SUBSCRIPTION, e.getMessage());
                        exThrown = true;
                    } finally {
                        assertTrue("nextMsg should have thrown an exception", exThrown);
                    }

                    exThrown = false;
                    try {
                        sub.nextMessage(200);
                        fail("Shouldn't be here");
                    } catch (Exception e) {
                        assertTrue(e instanceof IllegalStateException);
                        assertEquals(ERR_BAD_SUBSCRIPTION, e.getMessage());
                        exThrown = true;
                    } finally {
                        assertTrue("nextMsg should have thrown an exception", exThrown);
                    }
                } catch (Exception e) {
                    fail(e.getMessage());
                }
            }
        }
    }

    @Test
    public void testSlowSubscriber() throws IOException, TimeoutException {
        ConnectionFactory cf = new ConnectionFactory();
        cf.setMaxPendingMsgs(100);
        final AtomicBoolean exThrown = new AtomicBoolean(false);
        try (NATSServer srv = runDefaultServer()) {
            try (Connection c = cf.createConnection()) {
                try (SyncSubscription s = c.subscribeSync("foo")) {
                    s.setMaxPendingMsgs(100);
                    s.setMaxPendingBytes(1024);

                    for (int i = 0; i < (s.getMaxPendingMsgs() + 100); i++) {
                        c.publish("foo", "Hello".getBytes());
                    }

                    int timeout = 5000;
                    long t0 = System.nanoTime();
                    try {
                        c.flush(timeout);
                    } catch (Exception e) {
                        fail(e.getMessage());
                    }
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                    assertFalse(String.format("Flush did not return before timeout: %d >= %d",
                            elapsed, timeout), elapsed >= timeout);
                    exThrown.set(false);
                    try {
                        s.nextMessage(200);
                    } catch (IOException e) {
                        assertTrue(e instanceof IOException);
                        assertEquals(ERR_SLOW_CONSUMER, e.getMessage());
                        exThrown.set(true);
                    } finally {
                        assertTrue("nextMsg should have thrown an exception", exThrown.get());
                    }
                }
            }
        }
    }

    @Test
    public void testSlowChanSubscriber() {
        fail("not yet implemented");
    }

    @Test
    public void testSlowAsyncSubscriber() throws IOException, TimeoutException {
        ConnectionFactory cf = new ConnectionFactory();
        final Channel<Boolean> bch = new Channel<Boolean>();

        try (NATSServer srv = runDefaultServer()) {

            try (final Connection c = cf.createConnection()) {

                c.setExceptionHandler(null);
                try (final AsyncSubscriptionImpl s =
                        (AsyncSubscriptionImpl) c.subscribeAsync("foo", new MessageHandler() {
                            public void onMessage(Message msg) {
                                bch.get();
                            }
                        })) {

                    int pml = s.getMaxPendingMsgs();
                    assertEquals(ConnectionFactory.DEFAULT_MAX_PENDING_MSGS, pml);
                    long pbl = s.getMaxPendingBytes();
                    assertEquals(ConnectionFactory.DEFAULT_MAX_PENDING_BYTES, pbl);

                    // Set new limits
                    pml = 100;
                    pbl = 1024 * 1024;

                    s.setMaxPendingMsgs(pml);
                    s.setMaxPendingBytes(pbl);

                    assertEquals(pml, s.getMaxPendingMsgs());
                    assertEquals(pbl, s.getMaxPendingBytes());

                    for (int i = 0; i < (pml + 100); i++) {
                        c.publish("foo", "Hello".getBytes());
                    }

                    int flushTimeout = 5000;

                    long t0 = System.nanoTime();
                    long elapsed = 0L;
                    try {
                        c.flush(flushTimeout);
                    } catch (Exception e) {
                        e.printStackTrace();
                        fail("Should not have thrown exception: " + e.getMessage());
                    }

                    elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                    assertTrue("Flush did not return before timeout, elapsed msec=" + elapsed,
                            elapsed < flushTimeout);

                    assertTrue(c.getLastException() instanceof IOException);
                    assertEquals(Constants.ERR_SLOW_CONSUMER, c.getLastException().getMessage());

                    bch.add(true);
                    // System.err.printf("Delivered was %d, maxPendingMsgs was %d\n",
                    // s.delivered.get(),
                    // cf.getMaxPendingMsgs());
                }
            }
        }
    }

    @Test
    public void testAsyncErrHandler() throws IOException, TimeoutException {
        final String subj = "async_test";
        final AtomicInteger aeCalled = new AtomicInteger(0);

        ConnectionFactory cf = new ConnectionFactory();
        cf.setMaxPendingMsgs(10);

        final Channel<Boolean> ch = new Channel<Boolean>(1);
        final Channel<Boolean> bch = new Channel<Boolean>(1);

        final MessageHandler mcb = new MessageHandler() {
            @Override
            public void onMessage(Message msg) {
                if (aeCalled.get() == 1) {
                    return;
                }
                bch.get();
            }
        };
        try (NATSServer srv = runDefaultServer()) {

            try (Connection c = cf.createConnection()) {
                try (final AsyncSubscription s = c.subscribeAsync(subj, mcb)) {
                    c.setExceptionHandler(new ExceptionHandler() {
                        public void onException(NATSException ex) {
                            // Suppress additional calls
                            if (aeCalled.get() == 1) {
                                return;
                            }
                            aeCalled.incrementAndGet();

                            assertEquals("Did not receive proper subscription", s,
                                    ex.getSubscription());
                            assertTrue("Expected IOException, but got " + ex,
                                    ex.getCause() instanceof IOException);
                            assertEquals(ERR_SLOW_CONSUMER, ex.getCause().getMessage());

                            bch.add(true);

                            ch.add(true);

                        }
                    });

                    for (int i = 0; i < (cf.getMaxPendingMsgs() + 10); i++) {
                        c.publish(subj, "Hello World!".getBytes());
                    }
                    try {
                        c.flush(5000);
                    } catch (Exception e) {
                        /* NOOP */
                    }


                    assertTrue("Failed to call async err handler", ch.get(5000));

                } // AsyncSubscription
            } // Connection
        } // Server
    }

    @Test
    public void testAsyncErrHandlerChanSubscription() {
        fail("not yet implemented");
    }

    @Test
    public void testAsyncSubscriberStarvation() throws IOException, TimeoutException {
        final Channel<Boolean> ch = new Channel<Boolean>();

        try (NATSServer srv = runDefaultServer()) {
            try (final Connection c = new ConnectionFactory().createConnection()) {
                // Helper
                try (AsyncSubscription helper = c.subscribe("helper", new MessageHandler() {
                    public void onMessage(Message msg) {
                        // System.err.println("Helper");
                        sleep(100);
                        try {
                            c.publish(msg.getReplyTo(), "Hello".getBytes());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                })) {
                    // System.err.println("helper subscribed");
                    // Kickoff
                    try (AsyncSubscription start = c.subscribe("start", new MessageHandler() {
                        public void onMessage(Message msg) {
                            // System.err.println("Responder");
                            String responseInbox = c.newInbox();
                            c.subscribe(responseInbox, new MessageHandler() {
                                public void onMessage(Message msg) {
                                    // System.err.println("Internal subscriber.");
                                    sleep(100);
                                    ch.add(true);
                                }
                            });
                            // System.err.println("starter subscribed");
                            sleep(100);
                            try {
                                c.publish("helper", responseInbox, "Help me!".getBytes());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        } // "start" onMessage
                    })) {
                        UnitTestUtilities.sleep(100);
                        c.publish("start", "Begin".getBytes());
                        // System.err.println("Started");
                        assertTrue("Was stalled inside of callback waiting on another callback",
                                ch.get(5000));
                    } // Start
                } // Helper
            }
        }
    }

    @Test
    public void testAsyncSubscribersOnClose() throws IOException, TimeoutException {
        // Tests if the subscriber sub channel gets
        // cleared on a close.
        final AtomicInteger callbacks = new AtomicInteger(0);
        int toSend = 10;
        final Channel<Boolean> ch = new Channel<Boolean>(toSend);

        MessageHandler mh = new MessageHandler() {
            public void onMessage(Message msg) {
                callbacks.getAndIncrement();
                ch.get();
            }
        };

        try (NATSServer srv = runDefaultServer()) {
            try (Connection c = new ConnectionFactory().createConnection()) {
                try (AsyncSubscription s = c.subscribe("foo", mh)) {
                    for (int i = 0; i < toSend; i++) {
                        c.publish("foo", "Hello World!".getBytes());
                    }
                    try {
                        c.flush();
                    } catch (Exception e) {
                        fail("Flush failure: " + e.getMessage());
                    }
                    sleep(10);

                    /*
                     * Since callbacks for a given sub are invoked sequentially in the same thread,
                     * only one message has been dequeued from the subscription's message channel
                     * and delivered to the MessageHandler callback at this point. The callback is
                     * waiting on a boolean signal channel before proceeding.
                     * 
                     * Closing the connection will remove and close the subsriptions on that
                     * connection, which will clear our subscription's message channel of any queued
                     * message. Therefore, no matter how many times we signal ch below, only that
                     * one message will be processed.
                     */

                    c.close();

                    assertEquals(0, s.getQueuedMessageCount());

                    // Release callbacks
                    for (int i = 0; i < toSend; i++) {
                        ch.add(true);
                    }

                    sleep(10);

                    assertEquals(
                            String.format("Expected only one callback, received %d callbacks\n",
                                    callbacks.get()),
                            1, callbacks.get());
                }
            } // Connection
        } // Server
    }

    @Test
    public void testNextMsgCallOnAsyncSub() {
        fail("not yet implemented");
    }

    @Test
    public void testNextMsgCallOnClosedSub() {
        fail("not  yet implemented");
    }

    @Test
    public void testChanSubscriber() {
        fail("not  yet implemented");
    }

    @Test
    public void testChanQueueSubscriber() {
        fail("not  yet implemented");
    }

    @Test
    public void testChanSubscriberPendingLimits() {
        fail("not  yet implemented");
    }

    @Test
    public void testQueueChanQueueSubscriber() {
        fail("not  yet implemented");
    }

    @Test
    public void testUnsubscribeChanOnSubscriber() {
        fail("not  yet implemented");
    }

    @Test
    public void testCloseChanOnSubscriber() {
        fail("not  yet implemented");
    }


    @Test
    public void testAsyncSubscriptionPending() {
        fail("not  yet implemented");
    }

    @Test
    public void testAsyncSubscriptionPendingDrain() {
        fail("not  yet implemented");
    }

    @Test
    public void testSyncSubscriptionPendingDrain() {
        fail("not  yet implemented");
    }

    @Test
    public void testSyncSubscriptionPending() {
        fail("not  yet implemented");
    }

    @Test
    public void testSetPendingLimits() {
        fail("not  yet implemented");
    }

    @Test
    public void testSubscriptionTypes() {
        fail("not  yet implemented");
    }

    @Test
    public void testManyRequests() throws Exception {
        final int numRequests = 1000;
        try (NATSServer srv = runDefaultServer()) {
            ConnectionFactory cf = new ConnectionFactory(ConnectionFactory.DEFAULT_URL);
            try (final Connection conn = cf.createConnection()) {
                try (final Connection pub = cf.createConnection()) {
                    try (Subscription sub = conn.subscribe("foo", "bar", new MessageHandler() {
                        public void onMessage(Message message) {
                            try {
                                conn.publish(message.getReplyTo(), "hello".getBytes());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    })) {
                        conn.flush();
                        for (int i = 0; i < numRequests; i++) {
                            try {
                                assertNotNull(pub.request("foo", "blah".getBytes(), 5000));
                            } catch (TimeoutException e) {
                                fail(String.format("timed out after %d msgs", i + 1));
                                return;
                            }
                        } // for
                    } // Subscription
                } // pub
            } // conn
        } // server
    }
}
