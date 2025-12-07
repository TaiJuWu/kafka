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

import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.utils.Timer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Result of an asynchronous request from {@link ConsumerNetworkClient}. Use {@link ConsumerNetworkClient#poll(Timer)}
 * (and variants) to finish a request future. Use {@link #isDone()} to check if the future is complete, and
 * {@link #succeeded()} to check if the request completed successfully. Typical usage might look like this:
 *
 * <pre>
 *     RequestFuture<ClientResponse> future = client.send(api, request);
 *     client.poll(future);
 *
 *     if (future.succeeded()) {
 *         ClientResponse response = future.value();
 *         // Handle response
 *     } else {
 *         throw future.exception();
 *     }
 * </pre>
 *
 * @param <T> Return type of the result (Can be Void if there is no response)
 */
public class RequestFuture<T> implements ConsumerNetworkClient.PollCondition {

    private final CompletableFuture<T> completableFuture = new CompletableFuture<>();
    private final ConcurrentLinkedQueue<RequestFutureListener<T>> listeners = new ConcurrentLinkedQueue<>();

    /**
     * Check whether the response is ready to be handled
     * @return true if the response is ready, false otherwise
     */
    public boolean isDone() {
        return completableFuture.isDone();
    }

    /**
     * Await completion of the request
     * @param timeout maximum time to wait
     * @param unit time unit of timeout
     * @return true if completed within timeout, false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            completableFuture.get(timeout, unit);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            // Future completed exceptionally, which still means it's done
            return true;
        }
    }

    /**
     * Get the value corresponding to this request (only available if the request succeeded)
     * @return the value if the future completed successfully
     * @throws IllegalStateException if the future is not complete or failed
     */
    public T value() {
        if (!succeeded())
            throw new IllegalStateException("Attempt to retrieve value from future which hasn't successfully completed");
        try {
            return completableFuture.getNow(null);
        } catch (Exception e) {
            // This should not happen since we checked succeeded()
            throw new IllegalStateException("Unexpected exception retrieving value", e);
        }
    }

    /**
     * Check if the request succeeded;
     * @return true if the request completed and was successful
     */
    public boolean succeeded() {
        return completableFuture.isDone() && !completableFuture.isCompletedExceptionally() && !completableFuture.isCancelled();
    }

    /**
     * Check if the request failed.
     * @return true if the request completed with a failure
     */
    public boolean failed() {
        return completableFuture.isCompletedExceptionally();
    }

    /**
     * Check if the request is retriable. This is a convenience method for checking if
     * the exception is an instance of {@link RetriableException}.
     * @return true if it is retriable, false otherwise
     * @throws IllegalStateException if the future is not complete or completed successfully
     */
    public boolean isRetriable() {
        return exception() instanceof RetriableException;
    }

    /**
     * Get the exception from a failed result (only available if the request failed)
     * @return the exception set in {@link #raise(RuntimeException)}
     * @throws IllegalStateException if the future is not complete or completed successfully
     */
    public RuntimeException exception() {
        if (!failed())
            throw new IllegalStateException("Attempt to retrieve exception from future which hasn't failed");

        try {
            completableFuture.join();
            // Should not reach here since we checked failed()
            throw new IllegalStateException("Future is marked as failed but no exception found");
        } catch (java.util.concurrent.CompletionException e) {
            // CompletionException wraps the actual exception
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                return (RuntimeException) cause;
            } else {
                return new RuntimeException(cause);
            }
        } catch (RuntimeException e) {
            // Direct RuntimeException (shouldn't happen with completeExceptionally, but handle it)
            return e;
        }
    }

    /**
     * Complete the request successfully. After this call, {@link #succeeded()} will return true
     * and the value can be obtained through {@link #value()}.
     * @param value corresponding value (or null if there is none)
     * @throws IllegalStateException if the future has already been completed
     * @throws IllegalArgumentException if the argument is an instance of {@link RuntimeException}
     */
    public void complete(T value) {
        if (value instanceof RuntimeException)
            throw new IllegalArgumentException("The argument to complete can not be an instance of RuntimeException");

        if (!completableFuture.complete(value))
            throw new IllegalStateException("Invalid attempt to complete a request future which is already complete");

        fireSuccess();
    }

    private void fireSuccess() {
        T value = value();
        for (RequestFutureListener<T> listener : listeners) {
            listener.onSuccess(value);
        }
    }

    /**
     * Raise an exception. The request will be marked as failed, and the caller can either
     * handle the exception or throw it.
     * @param e corresponding exception to be passed to caller
     * @throws IllegalStateException if the future has already been completed
     */
    public void raise(RuntimeException e) {
        if (e == null)
            throw new IllegalArgumentException("The exception passed to raise must not be null");

        if (!completableFuture.completeExceptionally(e))
            throw new IllegalStateException("Invalid attempt to complete a request future which is already complete");

        fireFailure();
    }

    private void fireFailure() {
        RuntimeException exception = exception();
        for (RequestFutureListener<T> listener : listeners) {
            listener.onFailure(exception);
        }
    }

    /**
     * Raise an error. The request will be marked as failed.
     * @param error corresponding error to be passed to caller
     */
    public void raise(Errors error) {
        raise(error.exception());
    }

    /**
     * Add a listener which will be notified when the future completes
     * @param listener non-null listener to add
     */
    public void addListener(RequestFutureListener<T> listener) {
        listeners.add(listener);
        if (failed()) {
            listener.onFailure(exception());
        } else if (succeeded()) {
            listener.onSuccess(value());
        }
    }

    /**
     * Convert from a request future of one type to another type
     * @param adapter The adapter which does the conversion
     * @param <S> The type of the future adapted to
     * @return The new future
     */
    public <S> RequestFuture<S> compose(final RequestFutureAdapter<T, S> adapter) {
        final RequestFuture<S> adapted = new RequestFuture<>();
        addListener(new RequestFutureListener<>() {
            @Override
            public void onSuccess(T value) {
                adapter.onSuccess(value, adapted);
            }

            @Override
            public void onFailure(RuntimeException e) {
                adapter.onFailure(e, adapted);
            }
        });
        return adapted;
    }

    /**
     * Chain this future to another future. When this future completes, the chained future
     * will be completed with the same result.
     * @param future the future to chain to
     */
    public void chain(final RequestFuture<T> future) {
        addListener(new RequestFutureListener<>() {
            @Override
            public void onSuccess(T value) {
                future.complete(value);
            }

            @Override
            public void onFailure(RuntimeException e) {
                future.raise(e);
            }
        });
    }

    /**
     * Create a future that is already completed with a failure
     * @param e the exception
     * @param <T> the type parameter
     * @return a failed future
     */
    public static <T> RequestFuture<T> failure(RuntimeException e) {
        RequestFuture<T> future = new RequestFuture<>();
        future.raise(e);
        return future;
    }

    /**
     * Create a future that is already completed successfully with a void value
     * @return a successful void future
     */
    public static RequestFuture<Void> voidSuccess() {
        RequestFuture<Void> future = new RequestFuture<>();
        future.complete(null);
        return future;
    }

    /**
     * Create a future that failed with COORDINATOR_NOT_AVAILABLE error
     * @param <T> the type parameter
     * @return a failed future
     */
    public static <T> RequestFuture<T> coordinatorNotAvailable() {
        return failure(Errors.COORDINATOR_NOT_AVAILABLE.exception());
    }

    /**
     * Create a future that failed with no brokers available error
     * @param <T> the type parameter
     * @return a failed future
     */
    public static <T> RequestFuture<T> noBrokersAvailable() {
        return failure(new NoAvailableBrokersException());
    }

    @Override
    public boolean shouldBlock() {
        return !isDone();
    }

    /**
     * Get the underlying CompletableFuture for advanced usage
     * @return the CompletableFuture backing this RequestFuture
     */
    public CompletableFuture<T> toCompletableFuture() {
        return completableFuture;
    }
}
