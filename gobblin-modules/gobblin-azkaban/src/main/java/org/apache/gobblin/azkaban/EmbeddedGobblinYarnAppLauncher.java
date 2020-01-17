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

package org.apache.gobblin.azkaban;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Map;

import org.apache.gobblin.testing.AssertWithBackoff;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.MiniYARNCluster;
import org.testng.collections.Lists;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Closer;

import lombok.extern.slf4j.Slf4j;


/**
 * Given a set up Azkaban job configuration, launch the Gobblin-on-Yarn job in a semi-embedded mode:
 * - Uses external Kafka cluster
 */
@Slf4j
public class EmbeddedGobblinYarnAppLauncher extends AzkabanJobRunner {
  public static final String DYNAMIC_CONF_PATH = "dynamic.conf";
  public static final String YARN_SITE_XML_PATH = "yarn-site.xml";
  private static String zkString = "zk-ltx1-gobblin.stg.linkedin.com:6312";
  private static String fileAddress = "";

  private static void setup() throws Exception {
    // Initialize necessary external components: Yarn and Helix
    Closer closer = Closer.create();

    // Set java home in environment since it isn't set on some systems
    String javaHome = System.getProperty("java.home");
    setEnv("JAVA_HOME", javaHome);

    final YarnConfiguration clusterConf = new YarnConfiguration();
    clusterConf.set("yarn.resourcemanager.connect.max-wait.ms", "10000");
    clusterConf.set("yarn.nodemanager.resource.memory-mb", "512");
    clusterConf.set("yarn.scheduler.maximum-allocation-mb", "1024");

    MiniYARNCluster miniYARNCluster = closer.register(new MiniYARNCluster("TestCluster", 1, 1, 1));
    miniYARNCluster.init(clusterConf);
    miniYARNCluster.start();

    // YARN client should not be started before the Resource Manager is up
    AssertWithBackoff.create().logger(log).timeoutMs(10000)
        .assertTrue(new Predicate<Void>() {
          @Override public boolean apply(Void input) {
            return !clusterConf.get(YarnConfiguration.RM_ADDRESS).contains(":0");
          }
        }, "Waiting for RM");

    // Use a random ZK port
//    TestingServer testingZKServer = closer.register(new TestingServer(40086));
//    log.info("Testing ZK Server listening on: " + testingZKServer.getConnectString());

    // the zk port is dynamically configured
    try (PrintWriter pw = new PrintWriter(DYNAMIC_CONF_PATH, "UTF-8")) {
      File dir = new File("target/dummydir");

      // dummy directory specified in configuration
      // TODO: Somehow if throwing RuntimeException here, the program is not able to be recovered.
      if (!dir.mkdir()) {
        log.error("The dummy folder's creation is not successful");
      }
      dir.deleteOnExit();

      pw.println("gobblin.cluster.zk.connection.string=\"" + zkString + "\"");
      pw.println("jobconf.fullyQualifiedPath=\"" + dir.getAbsolutePath() + "\"");
    }

    // YARN config is dynamic and needs to be passed to other processes
    try (OutputStream os = new FileOutputStream(new File(YARN_SITE_XML_PATH))) {
      clusterConf.writeXml(os);
    }

    /** Have to pass the same yarn-site.xml to the GobblinYarnAppLauncher to initialize Yarn Client. */
    fileAddress = new File(YARN_SITE_XML_PATH).getAbsolutePath();

    EmbeddedGobblinYarnAppLauncher.zkString = "zk-ltx1-gobblin.stg.linkedin.com:6312";
  }

  public static void setEnv(String key, String value) {
    try {
      Map<String, String> env = System.getenv();
      Class<?> cl = env.getClass();
      Field field = cl.getDeclaredField("m");
      field.setAccessible(true);
      Map<String, String> writableEnv = (Map<String, String>) field.get(env);
      writableEnv.put(key, value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to set environment variable", e);
    }
  }

  public static void main(String[] args)
      throws Exception {
    setup();
    AzkabanJobRunner.doMain(EmbeddedGobblinYarnAppLauncher.class, args);
  }

  public EmbeddedGobblinYarnAppLauncher() {
    super(Lists.newArrayList("gobblin-modules/gobblin-azkaban/src/main/resources/conf/properties/common.properties",
        "gobblin-modules/gobblin-azkaban/src/main/resources/conf/properties/local.properties"),
        Lists.newArrayList("gobblin-modules/gobblin-azkaban/src/main/resources/conf/jobs/kafka-streaming-on-yarn.job"),
        ImmutableMap.of("yarn.resourcemanager.connect.max-wait.ms", "10000",
            "gobblin.cluster.zk.connection.string", EmbeddedGobblinYarnAppLauncher.zkString,
        "gobblin.cluster.job.conf.path", "gobblin-modules/gobblin-azkaban/src/main/resources/conf/gobblin_jobs",
        "gobblin.yarn.conf.dir", "gobblin-modules/gobblin-azkaban/src/main/resources/conf/gobblin_conf",
            "yarn-site-address", fileAddress));
  }
}
