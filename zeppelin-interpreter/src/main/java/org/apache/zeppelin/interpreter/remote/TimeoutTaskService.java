/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.interpreter.remote;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * TimeoutTaskService is a utility class designed to wrap a long-running operation and set a timeout for its execution.
 * It provides several services, some of which return values.
 */
class TimeoutTaskService
{
  private TimeoutTaskService(){}

  /**
   * Run the {@code task} with a timeout. Any {@link TimeoutException} or Exception during execution will be passed to the onThrow method, as
   * well as the {@link Future} object waiting on a response.
   * @param task the task to be performed
   * @param timeoutMillis timeout for the task, in milliseconds
   * @param onThrow hook called when timeout occurs or an exception is thrown
   * @param <T> the return type of the task
   * @return the value of the task if successful, otherwise the return value of onThrow will be returned
   */
  public static <T> T doTimeoutTask(Callable<T> task, int timeoutMillis,
                                    BiFunction<Future<T>, Exception, T> onThrow)
  {
    ExecutorService service = Executors.newSingleThreadExecutor();
    Future<T> future = null;
    try
    {
      future = service.submit(task);
      if (timeoutMillis == -1)
      {
        return future.get();
      }
      else
      {
        return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
      }
    }
    catch (InterruptedException | ExecutionException | TimeoutException e)
    {
      return onThrow.apply(future, e);
    }
    finally
    {
      service.shutdown();
    }
  }

  /**
   * Runs the task until completion or the timeout is reached. If the timeout is reached or any Exception is thrown in execution, onThrow will be
   * called with a {@link Future} object that's waiting on the task to complete.
   * @param task the task to be run
   * @param timeoutMillis the timeout
   * @param onThrow function called when either a timeout or an execution Exception occurs.
   */
  public static void doTimeoutTask(Runnable task, int timeoutMillis,
                                   BiConsumer<Future<?>, Exception> onThrow)
  {
    ExecutorService service = Executors.newSingleThreadExecutor();
    Future future = null;
    try
    {
      future = service.submit(task);
      if (timeoutMillis == -1)
      {
        future.get();
      }
      else
      {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS);
      }
    }
    catch (InterruptedException | ExecutionException | TimeoutException e)
    {
      onThrow.accept(future, e);
    }
    finally
    {
      service.shutdown();
    }
  }

  /**
   * Runs the provided task asynchronously while polling to make sure systems are still in an acceptable state. The task is run until:
   * 1) it completes
   * 2) the polling function returns false
   * 3) the call to the polling function doesn't complete within the timeout
   * 4) an exception is thrown
   *
   * If the task completes, then its return value is then returned to the caller. If any of the other three conditions occur, the return value of
   * the onThrow function will be returned.
   * @param task the task to be run
   * @param pollingFunction a function to check progress/sanity of the system.
   * @param timeoutMillis Specifies the timeout and calling interval for the polling function.
   * @param onThrow function called if a timeout or execution exception occurs. Its return value will be the return value of this function if called
   * @param <T> the return type of the task
   * @return the result of the task if it completes, otherwise the result of the onThrow function
   */
  public static <T> T pollingTimeoutTask(Callable<T> task,
                                         BooleanSupplier pollingFunction,
                                         int timeoutMillis,
                                         BiFunction<Future<T>, Exception, T> onThrow)
  {
    ExecutorService mainService = Executors.newSingleThreadExecutor();
    Future<T> mainResult = mainService.submit(task);
    try
    {
      while (!mainResult.isDone() && !mainResult.isCancelled())
      {
        long startTime = System.currentTimeMillis();
        boolean poll = TimeoutTaskService.doTimeoutTask(
                pollingFunction::getAsBoolean,
                timeoutMillis,
                (Future<Boolean> result, Exception e) -> false);
        if (!poll)
        {
          throw new TimeoutException("The polling task either timed out or returned false");
        }
        long diff = System.currentTimeMillis() - startTime;
        if(diff < timeoutMillis)
        {
          try
          {
            Thread.sleep(timeoutMillis - diff);
          }
          catch (InterruptedException e)
          {
            throw new RuntimeException("Polling timeout task interrupted", e);
          }
        }
      }
      return mainResult.get();
    }
    catch (Exception e)
    {
      return onThrow.apply(mainResult, e);
    }
    finally
    {
      mainService.shutdown();
    }
  }

}
