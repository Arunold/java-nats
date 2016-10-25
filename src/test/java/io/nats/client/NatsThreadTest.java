/*******************************************************************************
 * Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 * materials are made available under the terms of the MIT License (MIT) which accompanies this
 * distribution, and is available at http://opensource.org/licenses/MIT
 *******************************************************************************/

package io.nats.client;

import static io.nats.client.UnitTestUtilities.sleep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.concurrent.atomic.AtomicBoolean;

@Category(UnitTest.class)
public class NatsThreadTest implements Runnable {
    @Rule
    public TestCasePrinterRule pr = new TestCasePrinterRule(System.out);

    static final int NUM_THREADS = 5;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {}

    @AfterClass
    public static void tearDownAfterClass() throws Exception {}

    @Before
    public void setUp() throws Exception {}

    @After
    public void tearDown() throws Exception {}

    @Test
    public void testRun() {
        final AtomicBoolean exThrown = new AtomicBoolean(false);
        Thread.UncaughtExceptionHandler exh = new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable ex) {
                // System.out.println("Uncaught exception: " + ex);
                exThrown.set(true);
            }
        };
        NatsThread nt = new NatsThread(this);
        nt.setUncaughtExceptionHandler(exh);
        throwException = true;
        nt.start();
        try {
            Thread.sleep(100);
            nt.join();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        assertTrue(exThrown.get());
    }

    boolean throwException = false;

    @Override
    public void run() {
        try {
            if (throwException) {
                throw new Error("just for a test");
            }
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            /* NOOP */
        }
    }

    @Test
    public void testNATSThreadRunnable() {
        NatsThread[] threads = new NatsThread[NUM_THREADS];
        for (int i = 0; i < NUM_THREADS; i++) {
            NatsThread nt = new NatsThread(this);
            nt.start();
            threads[i] = nt;
        }
        sleep(100);

        assertTrue(NatsThread.getThreadsAlive() > 0);
        assertTrue(NatsThread.getThreadsCreated() > 0);
        NatsThread.setDebug(true);
        assertEquals(true, NatsThread.getDebug());
        NatsThread.setDebug(false);
        assertEquals(false, NatsThread.getDebug());
        NatsThread.setDebug(true);
        try {
            for (int i = 0; i < NUM_THREADS; i++) {
                {
                    threads[i].join(500);
                }
            }
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    // @Test
    // public void testNATSThreadRunnableString() {
    // fail("Not yet implemented"); // TODO
    // }
    //
    // @Test
    // public void testGetThreadsCreated() {
    // fail("Not yet implemented"); // TODO
    // }
    //
    // @Test
    // public void testGetThreadsAlive() {
    // fail("Not yet implemented"); // TODO
    // }
    //
    // @Test
    // public void testGetDebug() {
    // fail("Not yet implemented"); // TODO
    // }
    //
    // @Test
    // public void testSetDebug() {
    // fail("Not yet implemented"); // TODO
    // }
    //

}
