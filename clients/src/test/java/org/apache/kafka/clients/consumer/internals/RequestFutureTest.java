/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.protocol.Errors;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestFutureTest {

    @Test
    public void testBasicCompletion() {
        RequestFuture<String> future = new RequestFuture<>();
        String value = "foo";
        future.complete(value);
        assertTrue(future.isDone());
        assertEquals(value, future.value());
    }

    @Test
    public void testBasicFailure() {
        RequestFuture<String> future = new RequestFuture<>();
        RuntimeException exception = new RuntimeException();
        future.raise(exception);
        assertTrue(future.isDone());
        assertEquals(exception, future.exception());
    }

    @Test
    public void testVoidFuture() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.complete(null);
        assertTrue(future.isDone());
        assertNull(future.value());
    }

    @Test
    public void testRuntimeExceptionInComplete() {
        RequestFuture<Exception> future = new RequestFuture<>();
        assertThrows(IllegalArgumentException.class, () -> future.complete(new RuntimeException()));
    }

    @Test
    public void invokeCompleteAfterAlreadyComplete() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.complete(null);
        assertThrows(IllegalStateException.class, () -> future.complete(null));
    }

    @Test
    public void invokeCompleteAfterAlreadyFailed() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.raise(new RuntimeException());
        assertThrows(IllegalStateException.class, () -> future.complete(null));
    }

    @Test
    public void invokeRaiseAfterAlreadyFailed() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.raise(new RuntimeException());
        assertThrows(IllegalStateException.class, () -> future.raise(new RuntimeException()));
    }

    @Test
    public void invokeRaiseAfterAlreadyCompleted() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.complete(null);
        assertThrows(IllegalStateException.class, () -> future.raise(new RuntimeException()));
    }

    @Test
    public void invokeExceptionAfterSuccess() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.complete(null);
        assertThrows(IllegalStateException.class, future::exception);
    }

    @Test
    public void invokeValueAfterFailure() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.raise(new RuntimeException());
        assertThrows(IllegalStateException.class, future::value);
    }

    @Test
    public void listenerInvokedIfAddedBeforeFutureCompletion() {
        RequestFuture<Void> future = new RequestFuture<>();

        MockRequestFutureListener<Void> listener = new MockRequestFutureListener<>();
        future.addListener(listener);

        future.complete(null);

        assertOnSuccessInvoked(listener);
    }

    @Test
    public void listenerInvokedIfAddedBeforeFutureFailure() {
        RequestFuture<Void> future = new RequestFuture<>();

        MockRequestFutureListener<Void> listener = new MockRequestFutureListener<>();
        future.addListener(listener);

        future.raise(new RuntimeException());

        assertOnFailureInvoked(listener);
    }

    @Test
    public void listenerInvokedIfAddedAfterFutureCompletion() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.complete(null);

        MockRequestFutureListener<Void> listener = new MockRequestFutureListener<>();
        future.addListener(listener);

        assertOnSuccessInvoked(listener);
    }

    @Test
    public void listenerInvokedIfAddedAfterFutureFailure() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.raise(new RuntimeException());

        MockRequestFutureListener<Void> listener = new MockRequestFutureListener<>();
        future.addListener(listener);

        assertOnFailureInvoked(listener);
    }

    @Test
    public void listenersInvokedIfAddedBeforeAndAfterFailure() {
        RequestFuture<Void> future = new RequestFuture<>();

        MockRequestFutureListener<Void> beforeListener = new MockRequestFutureListener<>();
        future.addListener(beforeListener);

        future.raise(new RuntimeException());

        MockRequestFutureListener<Void> afterListener = new MockRequestFutureListener<>();
        future.addListener(afterListener);

        assertOnFailureInvoked(beforeListener);
        assertOnFailureInvoked(afterListener);
    }

    @Test
    public void listenersInvokedIfAddedBeforeAndAfterCompletion() {
        RequestFuture<Void> future = new RequestFuture<>();

        MockRequestFutureListener<Void> beforeListener = new MockRequestFutureListener<>();
        future.addListener(beforeListener);

        future.complete(null);

        MockRequestFutureListener<Void> afterListener = new MockRequestFutureListener<>();
        future.addListener(afterListener);

        assertOnSuccessInvoked(beforeListener);
        assertOnSuccessInvoked(afterListener);
    }

    @Test
    public void testComposeSuccessCase() {
        RequestFuture<String> future = new RequestFuture<>();
        RequestFuture<Integer> composed = future.compose(new RequestFutureAdapter<>() {
            @Override
            public void onSuccess(String value, RequestFuture<Integer> future) {
                future.complete(value.length());
            }
        });

        future.complete("hello");

        assertTrue(composed.isDone());
        assertTrue(composed.succeeded());
        assertEquals(5, (int) composed.value());
    }

    @Test
    public void testComposeFailureCase() {
        RequestFuture<String> future = new RequestFuture<>();
        RequestFuture<Integer> composed = future.compose(new RequestFutureAdapter<>() {
            @Override
            public void onSuccess(String value, RequestFuture<Integer> future) {
                future.complete(value.length());
            }
        });

        RuntimeException e = new RuntimeException();
        future.raise(e);

        assertTrue(composed.isDone());
        assertTrue(composed.failed());
        assertEquals(e, composed.exception());
    }

    @Test
    public void testChainSuccess() {
        RequestFuture<String> source = new RequestFuture<>();
        RequestFuture<String> chained = new RequestFuture<>();

        source.chain(chained);
        source.complete("test");

        assertTrue(chained.isDone());
        assertTrue(chained.succeeded());
        assertEquals("test", chained.value());
    }

    @Test
    public void testChainFailure() {
        RequestFuture<String> source = new RequestFuture<>();
        RequestFuture<String> chained = new RequestFuture<>();
        RuntimeException e = new RuntimeException("test error");

        source.chain(chained);
        source.raise(e);

        assertTrue(chained.isDone());
        assertTrue(chained.failed());
        assertSame(e, chained.exception());
    }

    @Test
    public void testStaticFactoryMethods() {
        // Test voidSuccess
        RequestFuture<Void> voidFuture = RequestFuture.voidSuccess();
        assertTrue(voidFuture.succeeded());
        assertNull(voidFuture.value());

        // Test coordinatorNotAvailable
        RequestFuture<String> coordFuture = RequestFuture.coordinatorNotAvailable();
        assertTrue(coordFuture.failed());
        assertEquals(Errors.COORDINATOR_NOT_AVAILABLE.exception().getClass(),
                     coordFuture.exception().getClass());

        // Test noBrokersAvailable
        RequestFuture<String> brokerFuture = RequestFuture.noBrokersAvailable();
        assertTrue(brokerFuture.failed());
        assertTrue(brokerFuture.exception() instanceof NoAvailableBrokersException);

        // Test failure
        RuntimeException e = new RuntimeException("test");
        RequestFuture<String> failureFuture = RequestFuture.failure(e);
        assertTrue(failureFuture.failed());
        assertSame(e, failureFuture.exception());
    }

    @Test
    public void testIsRetriable() {
        // Test with retriable exception
        RequestFuture<String> retriableFuture = new RequestFuture<>();
        retriableFuture.raise(new TimeoutException("timeout"));
        assertTrue(retriableFuture.isRetriable());

        // Test with non-retriable exception
        RequestFuture<String> nonRetriableFuture = new RequestFuture<>();
        nonRetriableFuture.raise(new AuthenticationException("auth failed"));
        assertFalse(nonRetriableFuture.isRetriable());
    }

    @Test
    public void testRaiseWithErrors() {
        RequestFuture<String> future = new RequestFuture<>();
        future.raise(Errors.NETWORK_EXCEPTION);

        assertTrue(future.failed());
        assertEquals(Errors.NETWORK_EXCEPTION.exception().getClass(),
                     future.exception().getClass());
    }

    @Test
    public void testRaiseWithNullException() {
        RequestFuture<String> future = new RequestFuture<>();
        assertThrows(IllegalArgumentException.class, () -> future.raise((RuntimeException) null));
    }

    @Test
    public void testShouldBlock() {
        RequestFuture<String> future = new RequestFuture<>();
        assertTrue(future.shouldBlock());

        future.complete("test");
        assertFalse(future.shouldBlock());
    }

    @Test
    public void testMultipleListenersExecuteInOrder() {
        RequestFuture<String> future = new RequestFuture<>();
        AtomicInteger counter = new AtomicInteger(0);
        AtomicInteger firstListenerValue = new AtomicInteger(-1);
        AtomicInteger secondListenerValue = new AtomicInteger(-1);
        AtomicInteger thirdListenerValue = new AtomicInteger(-1);

        future.addListener(new RequestFutureListener<String>() {
            @Override
            public void onSuccess(String value) {
                firstListenerValue.set(counter.getAndIncrement());
            }

            @Override
            public void onFailure(RuntimeException e) {
            }
        });

        future.addListener(new RequestFutureListener<String>() {
            @Override
            public void onSuccess(String value) {
                secondListenerValue.set(counter.getAndIncrement());
            }

            @Override
            public void onFailure(RuntimeException e) {
            }
        });

        future.addListener(new RequestFutureListener<String>() {
            @Override
            public void onSuccess(String value) {
                thirdListenerValue.set(counter.getAndIncrement());
            }

            @Override
            public void onFailure(RuntimeException e) {
            }
        });

        future.complete("test");

        // Listeners should execute in FIFO order
        assertEquals(0, firstListenerValue.get());
        assertEquals(1, secondListenerValue.get());
        assertEquals(2, thirdListenerValue.get());
    }

    @Test
    public void testListenersReceiveCorrectValue() {
        RequestFuture<String> future = new RequestFuture<>();
        AtomicReference<String> receivedValue = new AtomicReference<>();

        future.addListener(new RequestFutureListener<String>() {
            @Override
            public void onSuccess(String value) {
                receivedValue.set(value);
            }

            @Override
            public void onFailure(RuntimeException e) {
            }
        });

        future.complete("expected-value");

        assertEquals("expected-value", receivedValue.get());
    }

    @Test
    public void testListenersReceiveCorrectException() {
        RequestFuture<String> future = new RequestFuture<>();
        AtomicReference<RuntimeException> receivedException = new AtomicReference<>();
        RuntimeException expectedException = new RuntimeException("expected-error");

        future.addListener(new RequestFutureListener<String>() {
            @Override
            public void onSuccess(String value) {
            }

            @Override
            public void onFailure(RuntimeException e) {
                receivedException.set(e);
            }
        });

        future.raise(expectedException);

        assertSame(expectedException, receivedException.get());
    }

    @Test
    public void testConcurrentListenerAddition() throws InterruptedException {
        RequestFuture<String> future = new RequestFuture<>();
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(10);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Start 10 threads that add listeners
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for signal to start
                    future.addListener(new RequestFutureListener<String>() {
                        @Override
                        public void onSuccess(String value) {
                            callCount.incrementAndGet();
                        }

                        @Override
                        public void onFailure(RuntimeException e) {
                        }
                    });
                    latch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        // Start all threads at once
        startLatch.countDown();

        // Wait for all threads to add listeners
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Complete the future
        future.complete("test");

        // All 10 listeners should have been called
        assertEquals(10, callCount.get());
    }

    @Test
    public void testComposeWithAdapterThatFails() {
        RequestFuture<String> future = new RequestFuture<>();
        RuntimeException adapterException = new RuntimeException("adapter error");

        RequestFuture<Integer> composed = future.compose(new RequestFutureAdapter<String, Integer>() {
            @Override
            public void onSuccess(String value, RequestFuture<Integer> future) {
                future.raise(adapterException);
            }

            @Override
            public void onFailure(RuntimeException e, RequestFuture<Integer> future) {
                future.raise(e);
            }
        });

        future.complete("test");

        assertTrue(composed.failed());
        assertSame(adapterException, composed.exception());
    }

    @Test
    public void testComposeWithAdapterThatTransformsError() {
        RequestFuture<String> future = new RequestFuture<>();
        RuntimeException originalException = new RuntimeException("original");
        RuntimeException transformedException = new RuntimeException("transformed");

        RequestFuture<Integer> composed = future.compose(new RequestFutureAdapter<String, Integer>() {
            @Override
            public void onSuccess(String value, RequestFuture<Integer> future) {
                future.complete(value.length());
            }

            @Override
            public void onFailure(RuntimeException e, RequestFuture<Integer> future) {
                future.raise(transformedException);
            }
        });

        future.raise(originalException);

        assertTrue(composed.failed());
        assertSame(transformedException, composed.exception());
    }

    @Test
    public void testSucceededReturnsTrueOnlyWhenCompleted() {
        RequestFuture<String> future = new RequestFuture<>();
        assertFalse(future.succeeded());

        future.complete("test");
        assertTrue(future.succeeded());
    }

    @Test
    public void testSucceededReturnsFalseWhenFailed() {
        RequestFuture<String> future = new RequestFuture<>();
        future.raise(new RuntimeException());
        assertFalse(future.succeeded());
    }

    @Test
    public void testFailedReturnsTrueOnlyWhenRaised() {
        RequestFuture<String> future = new RequestFuture<>();
        assertFalse(future.failed());

        future.raise(new RuntimeException());
        assertTrue(future.failed());
    }

    @Test
    public void testFailedReturnsFalseWhenSucceeded() {
        RequestFuture<String> future = new RequestFuture<>();
        future.complete("test");
        assertFalse(future.failed());
    }

    @Test
    public void testListenerExceptionPropagates() {
        RequestFuture<String> future = new RequestFuture<>();
        RuntimeException listenerException = new RuntimeException("listener error");

        future.addListener(new RequestFutureListener<String>() {
            @Override
            public void onSuccess(String value) {
                throw listenerException;
            }

            @Override
            public void onFailure(RuntimeException e) {
            }
        });

        // Listener exceptions should propagate during complete()
        assertThrows(RuntimeException.class, () -> future.complete("test"));
    }

    @Test
    public void testListenerExceptionOnFailurePropagates() {
        RequestFuture<String> future = new RequestFuture<>();
        RuntimeException listenerException = new RuntimeException("listener error");

        future.addListener(new RequestFutureListener<String>() {
            @Override
            public void onSuccess(String value) {
            }

            @Override
            public void onFailure(RuntimeException e) {
                throw listenerException;
            }
        });

        // Listener exceptions should propagate during raise()
        assertThrows(RuntimeException.class, () -> future.raise(new RuntimeException("original")));
    }

    private static <T> void assertOnSuccessInvoked(MockRequestFutureListener<T> listener) {
        assertEquals(1, listener.numOnSuccessCalls.get());
        assertEquals(0, listener.numOnFailureCalls.get());
    }

    private static <T> void assertOnFailureInvoked(MockRequestFutureListener<T> listener) {
        assertEquals(0, listener.numOnSuccessCalls.get());
        assertEquals(1, listener.numOnFailureCalls.get());
    }

    private static class MockRequestFutureListener<T> implements RequestFutureListener<T> {
        private final AtomicInteger numOnSuccessCalls = new AtomicInteger(0);
        private final AtomicInteger numOnFailureCalls = new AtomicInteger(0);

        @Override
        public void onSuccess(T value) {
            numOnSuccessCalls.incrementAndGet();
        }

        @Override
        public void onFailure(RuntimeException e) {
            numOnFailureCalls.incrementAndGet();
        }
    }

}
