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

package gobblin.publisher;

import com.google.common.base.Splitter;
import gobblin.hive.metastore.HiveMetaStoreUtils;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.fs.Path;

import com.google.common.base.Optional;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;

import gobblin.configuration.ConfigurationKeys;
import gobblin.configuration.State;
import gobblin.configuration.WorkUnitState;
import gobblin.hive.HiveRegProps;
import gobblin.hive.HiveRegister;
import gobblin.hive.policy.HiveRegistrationPolicy;
import gobblin.hive.policy.HiveRegistrationPolicyBase;
import gobblin.hive.spec.HiveSpec;
import gobblin.util.ExecutorsUtils;

import lombok.extern.slf4j.Slf4j;


/**
 * A {@link DataPublisher} that registers the already published data with Hive.
 *
 * <p>
 *   This publisher is not responsible for publishing data, and it relies on another publisher
 *   to document the published paths in property {@link ConfigurationKeys#PUBLISHER_DIRS}. Thus this publisher
 *   should generally be used as a job level data publisher, where the task level publisher should be a publisher
 *   that documents the published paths, such as {@link BaseDataPublisher}.
 * </p>
 *
 * @author Ziyang Liu
 */
@Slf4j
public class HiveRegistrationPublisher extends DataPublisher {

  private static final String DATA_PUBLISH_TIME = HiveRegistrationPublisher.class.getName() + ".lastDataPublishTime";
  private static final Splitter LIST_SPLITTER_COMMA = Splitter.on(",").trimResults().omitEmptyStrings();
  private final Closer closer = Closer.create();
  private final HiveRegister hiveRegister;
  private final ExecutorService hivePolicyExecutor;

  public HiveRegistrationPublisher(State state) {
    super(state);
    this.hiveRegister = this.closer.register(HiveRegister.get(state));
    this.hivePolicyExecutor = ExecutorsUtils.loggingDecorator(Executors.newFixedThreadPool(new HiveRegProps(state).getNumThreads(),
        ExecutorsUtils.newThreadFactory(Optional.of(log), Optional.of("HivePolicyExecutor-%d"))));
  }

  @Override
  public void close() throws IOException {
    try {
      ExecutorsUtils.shutdownExecutorService(this.hivePolicyExecutor, Optional.of(log));
    } finally {
      this.closer.close();
    }
  }

  @Deprecated
  @Override
  public void initialize() throws IOException {}

  @Override
  public void publishData(Collection<? extends WorkUnitState> states) throws IOException {
    CompletionService<Collection<HiveSpec>> completionService =
        new ExecutorCompletionService<>(this.hivePolicyExecutor);

    State superState = super.state;
    int numberOfPathsToRegister = getNumberOfPathsToRegister(states);

    // Each state in states is task-level State, while superState is the Job-level State.
    // Using both State objects to distinguish each HiveRegistrationPolicy so that
    // they can carry task-level information to pass into Hive Partition and its corresponding Hive Table.

    // Here all runtime task-level props are injected into superstate which installed in each Policy Object.
    // runtime.props are comma-separated props collected in runtime.
    Set<String> pathsToRegisterFromSingleState = Sets.newHashSet();
    for (State state:states) {
      if (state.contains(ConfigurationKeys.PUBLISHER_DIRS)) {
        // Upstream data attribute is specified, need to inject these info into superState as runtimeTableProps.
        if (this.hiveRegister.getProps().getUpstreamDataAttrName().isPresent()) {
          for (String attrName:
              LIST_SPLITTER_COMMA.splitToList(this.hiveRegister.getProps().getUpstreamDataAttrName().get())){
            if (state.contains(attrName)) {
              superState.appendToListProp(HiveMetaStoreUtils.RUNTIME_PROPS,
                    attrName + ":" + state.getProp(attrName));
            }
          }
        }

        final HiveRegistrationPolicy policy = HiveRegistrationPolicyBase.getPolicy(superState);
        for ( final String path : state.getPropAsList(ConfigurationKeys.PUBLISHER_DIRS) ) {
          if (pathsToRegisterFromSingleState.contains(path)) {
            continue;
          }
          pathsToRegisterFromSingleState.add(path);
          completionService.submit(new Callable<Collection<HiveSpec>>() {
            @Override
            public Collection<HiveSpec> call() throws Exception {
              return policy.getHiveSpecs(new Path(path));
            }
          });
        }
      }
      else continue;
    }
    for (int i = 0; i < numberOfPathsToRegister; i++) {
      try {
        for (HiveSpec spec : completionService.take().get()) {
          this.hiveRegister.register(spec);
        }
      } catch (InterruptedException | ExecutionException e) {
        log.info("Failed to generate HiveSpec", e);
        throw new IOException(e);
      }
    }
    log.info("Finished registering all HiveSpecs");
  }

  private static int getNumberOfPathsToRegister(Collection<? extends WorkUnitState> states) {
    int pathsCount = 0;
    for (State state : states) {
      if (state.contains(ConfigurationKeys.PUBLISHER_DIRS)) {
        pathsCount += (state.getPropAsList(ConfigurationKeys.PUBLISHER_DIRS)).size();
      }
    }
    return pathsCount;
  }

  @Override
  public void publishMetadata(Collection<? extends WorkUnitState> states) throws IOException {
    // Nothing to do
  }

  private static void addRuntimeHiveRegistrationProperties(State state) {
    // Use seconds instead of milliseconds to be consistent with other times stored in hive
    state.appendToListProp(HiveRegProps.HIVE_TABLE_PARTITION_PROPS,
        String.format("%s:%d", DATA_PUBLISH_TIME, TimeUnit.SECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS)));
  }
}
