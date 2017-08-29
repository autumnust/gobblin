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

package org.apache.gobblin.service;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Future;

import com.google.common.base.Optional;
import com.typesafe.config.Config;
import com.google.common.io.Closer;

import org.slf4j.Logger;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.runtime.api.Spec;
import org.apache.gobblin.util.CompletedFuture;
import org.apache.gobblin.util.ConfigUtils;
import org.apache.gobblin.runtime.api.SpecExecutor;
import org.apache.gobblin.runtime.api.SpecConsumer;
import org.apache.gobblin.runtime.api.SpecProducer;
import org.apache.gobblin.runtime.spec_executorInstance.AbstractSpecExecutor;

/**
 * An {@link SpecExecutor} that use Kafka as the communication mechanism.
 */
public class SimpleKafkaSpecExecutor extends AbstractSpecExecutor {
  public static final String SPEC_KAFKA_TOPICS_KEY = "spec.kafka.topics";

  // Executor Instance
  protected final URI specExecutorInstanceUri;

  protected static final String VERB_KEY = "Verb";

  private SpecProducer<Spec> specProducer;

  private SpecConsumer<Spec> specConsumer;

  public SimpleKafkaSpecExecutor(Config config, Optional<Logger> log) {
    super(config, log);
    try {
      specExecutorInstanceUri = new URI(ConfigUtils.getString(config, ConfigurationKeys.SPECEXECUTOR_INSTANCE_URI_KEY,
          "NA"));
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    specProducer = new SimpleKafkaSpecProducer(config, log);
    specConsumer = new SimpleKafkaSpecConsumer(config, log);
  }

  @Override
  public Future<? extends SpecProducer> getProducer() {
    return new CompletedFuture<>(this.specProducer, null);
  }

  @Override
  public URI getUri() {
    return specExecutorInstanceUri;
  }

  @Override
  public Future<String> getDescription() {
    return new CompletedFuture<>("SimpleSpecExecutorInstance with URI: " + specExecutorInstanceUri, null);
  }

  @Override
  protected void startUp() throws Exception {
    _optionalCloser = Optional.of(Closer.create());
    specProducer = _optionalCloser.get().register((SimpleKafkaSpecProducer) specProducer);
    specConsumer = _optionalCloser.get().register((SimpleKafkaSpecConsumer) specConsumer);
  }

  @Override
  protected void shutDown() throws Exception {
    if (_optionalCloser.isPresent()) {
      _optionalCloser.get().close();
    } else {
      throw new RuntimeException("Closer initialization failed");
    }
  }

  public static class SpecExecutorInstanceDataPacket implements Serializable {

    protected Verb _verb;
    protected URI _uri;
    protected Spec _spec;

    public SpecExecutorInstanceDataPacket(Verb verb, URI uri, Spec spec) {
      _verb = verb;
      _uri = uri;
      _spec = spec;
    }

    @Override
    public String toString() {
      return String.format("Verb: %s, URI: %s, Spec: %s", _verb, _uri, _spec);
    }
  }
}