/**
 * Copyright 2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.collector.eventhub;

import java.io.InterruptedIOException;
import java.security.InvalidKeyException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import zipkin.storage.InMemoryStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.isA;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.verifyZeroInteractions;

public class LazyRegisterEventProcessorFactoryWithHostTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  CompletableFuture<Object> registration = new CompletableFuture<>();
  AtomicBoolean unregistered = new AtomicBoolean();

  /** Calling this ensures the main thread's interrupt status isn't tainted */
  @After public void uninterrupt(){
    Thread.currentThread().interrupted();
  }

  @Test
  public void get_registersFactory() throws Exception {
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost();

    lazy.get();

    assertThat(registration).isCompleted();
  }

  @Test
  public void get_doesntWrapRuntimeException() throws Exception {
    RuntimeException exception = new RuntimeException("Failure initializing Storage lease manager");
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost() {
      @Override Future<?> registerEventProcessorFactoryWithHost() throws Exception {
        throw exception;
      }
    };

    thrown.expect(is(exception));
    lazy.get();
  }

  @Test
  public void get_wrapsCheckedException() throws Exception {
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost() {
      @Override Future<?> registerEventProcessorFactoryWithHost() throws InvalidKeyException {
        throw new InvalidKeyException();
      }
    };

    thrown.expect(RuntimeException.class);
    thrown.expectCause(isA(InvalidKeyException.class));
    lazy.get();
  }

  @Test
  public void close_unregistersFactoryWhenOpen() throws Exception {
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost();

    lazy.get();
    lazy.close();

    assertThat(unregistered.get()).isTrue();
  }

  @Test
  public void close_doesntUnregisterFactoryWhenNotYetOpen() throws Exception {
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost();

    lazy.close();

    assertThat(unregistered.get()).isFalse();
  }

  @Test
  public void close_cancelsPendingRegistration() throws Exception {
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost() {
      @Override Future<?> registerEventProcessorFactoryWithHost() throws InvalidKeyException {
        return registration; // note: we aren't setting this done!
      }
    };

    lazy.get();
    lazy.close();

    assertThat(registration).isCancelled();
    assertThat(unregistered.get()).isTrue();
  }

  @Test
  public void close_doesntWrapRuntimeException() throws Exception {
    RuntimeException exception = new RuntimeException("Failure initializing Storage lease manager");
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost() {
      @Override void unregisterEventProcessorFactoryFromHost() {
        throw exception;
      }
    };

    lazy.get();
    thrown.expect(is(exception));
    lazy.close();
  }

  @Test
  public void close_UnwrapsExecutionException() throws Exception {
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost() {
      @Override void unregisterEventProcessorFactoryFromHost() throws ExecutionException {
        throw new ExecutionException(new RuntimeException());
      }
    };

    lazy.get();
    thrown.expect(RuntimeException.class);
    lazy.close();
  }

  @Test
  public void close_wrapsInterruptedAndSetsFlag() throws Exception {
    LazyRegisterEventProcessorFactoryWithHost
        lazy = new TestLazyRegisterEventProcessorFactoryWithHost() {
      @Override void unregisterEventProcessorFactoryFromHost() throws InterruptedException {
        throw new InterruptedException();
      }
    };

    lazy.get();
    try {
      lazy.close();
    } catch (InterruptedIOException e) {
      assertThat(e).hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
  }

  class TestLazyRegisterEventProcessorFactoryWithHost
      extends LazyRegisterEventProcessorFactoryWithHost {
    TestLazyRegisterEventProcessorFactoryWithHost() {
      super(EventHubCollector.newBuilder()
          .connectionString(
              "endpoint=sb://someurl.net;SharedAccessKeyName=dumbo;SharedAccessKey=uius7y8ewychsih")
          .storageConnectionString("UseDevelopmentStorage=true")
          .storage(new InMemoryStorage()));
    }

    @Override Future<?> registerEventProcessorFactoryWithHost() throws Exception {
      registration.complete("foo");
      return registration;
    }

    @Override void unregisterEventProcessorFactoryFromHost()
        throws InterruptedException, ExecutionException {
      unregistered.set(true);
    }
  }
}
