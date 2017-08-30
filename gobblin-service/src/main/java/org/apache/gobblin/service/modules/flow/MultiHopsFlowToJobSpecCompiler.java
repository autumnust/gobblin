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

package org.apache.gobblin.service.modules.flow;

import com.google.common.base.Splitter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.typesafe.config.Config;

import org.apache.commons.lang3.StringUtils;
import org.apache.gobblin.runtime.spec_executorInstance.InMemorySpecExecutor;
import org.apache.gobblin.service.modules.policy.ServicePolicy;
import org.apache.gobblin.util.ClassAliasResolver;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.slf4j.Logger;
import org.apache.gobblin.runtime.api.FlowEdge;
import org.apache.gobblin.runtime.api.ServiceNode;
import org.apache.gobblin.runtime.api.FlowSpec;
import org.apache.gobblin.instrumented.Instrumented;
import org.apache.gobblin.runtime.api.Spec;
import org.apache.gobblin.runtime.api.TopologySpec;
import org.apache.gobblin.service.ServiceConfigKeys;
import org.apache.gobblin.runtime.spec_executorInstance.BaseServiceNodeImpl;
import org.apache.gobblin.runtime.api.JobSpec;
import org.apache.gobblin.runtime.api.JobTemplate;
import org.apache.gobblin.runtime.api.SpecExecutor;
import org.apache.gobblin.runtime.api.SpecNotFoundException;
import org.apache.gobblin.runtime.job_spec.ResolvedJobSpec;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static org.apache.gobblin.service.ServiceConfigKeys.*;
import static org.apache.gobblin.service.modules.utils.findPathUtils.*;

// Users are capable to inject hints/prioritization into route selection, in two forms:
// 1. PolicyBasedBlockedConnection: Define some undesired routes
// 2. Specified a complete path. FlowCompiler is responsible to verify if the path given is valid.

// TODO: Flow monitoring, injecting weight for flowEdge:ETL-6213
@Slf4j
public class MultiHopsFlowToJobSpecCompiler extends BaseFlowToJobSpecCompiler {

  private static final Splitter SPLIT_BY_COMMA = Splitter.on(",").omitEmptyStrings().trimResults();

  @Getter
  private DirectedWeightedMultigraph<ServiceNode, FlowEdge> weightedGraph =
      new DirectedWeightedMultigraph<>(LoadBasedFlowEdgeImpl.class);

  ServicePolicy servicePolicy;

  // Contains user-specified complete path of how the data movement is executed from source to sink.
  private Optional<String> optionalUserSpecifiedPath;

  private FlowEdgeProps defaultFlowEdgeProps = new FlowEdgeProps();

  public MultiHopsFlowToJobSpecCompiler(Config config){
    this(config, Optional.absent(), true);
  }

  public MultiHopsFlowToJobSpecCompiler(Config config, Optional<Logger> log){
    this(config, log, true);
  }

  public MultiHopsFlowToJobSpecCompiler(Config config, Optional<Logger> log, boolean instrumentationEnabled) {
    super(config, log, instrumentationEnabled);
    String policyClassName = config.hasPath(SERVICE_POLICY_NAME)
        ? config.getString(SERVICE_POLICY_NAME) : ServiceConfigKeys.DEFAULT_SERVICE_POLICY;
    ClassAliasResolver<ServicePolicy> classResolver =
        new ClassAliasResolver<>(ServicePolicy.class);
    try {
      servicePolicy = classResolver.resolveClass(policyClassName).newInstance();
    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
      throw new RuntimeException("Error happen when resolving class for :" + policyClassName, e);
    }

    if (config.hasPath(ServiceConfigKeys.POLICY_BASED_BLOCKED_CONNECTION) &&
        config.getStringList(ServiceConfigKeys.POLICY_BASED_BLOCKED_CONNECTION).size() > 0) {
      try {
        for (String sourceSinkPair : config.getStringList(ServiceConfigKeys.POLICY_BASED_BLOCKED_CONNECTION)) {
          BaseServiceNodeImpl source = new BaseServiceNodeImpl(sourceSinkPair.split(":")[0]);
          BaseServiceNodeImpl sink = new BaseServiceNodeImpl(sourceSinkPair.split(":")[1]);
          URI specExecutorURI = new URI(sourceSinkPair.split(":")[2]);
          servicePolicy.addFlowEdge(new LoadBasedFlowEdgeImpl(source, sink,
              InMemorySpecExecutor.createDummySpecExecutor(specExecutorURI)));
        }
      } catch (URISyntaxException e) {
        this.log.warn("Constructing of FlowEdge in ServicePolicy Failed");
      }
    }

    if (config.hasPath(ServiceConfigKeys.POLICY_BASED_BLOCKED_NODES) &&
        config.getStringList(ServiceConfigKeys.POLICY_BASED_BLOCKED_NODES).size() > 0) {
      for (String blacklistedNode: SPLIT_BY_COMMA.splitToList(config.getString(ServiceConfigKeys.POLICY_BASED_BLOCKED_NODES))){
        servicePolicy.addServiceNode(new BaseServiceNodeImpl(blacklistedNode));
      }
    }

    if (config.hasPath(ServiceConfigKeys.POLICY_BASED_DATA_MOVEMENT_PATH) &&
        StringUtils.isNotBlank(config.getString(ServiceConfigKeys.POLICY_BASED_DATA_MOVEMENT_PATH))) {
      optionalUserSpecifiedPath = Optional.of(config.getString(ServiceConfigKeys.POLICY_BASED_DATA_MOVEMENT_PATH));
    } else {
      optionalUserSpecifiedPath = Optional.absent();
    }
  }

  @Override
  public Map<Spec, SpecExecutor> compileFlow(Spec spec) {
    // A Map from JobSpec to SpexExecutor
    Map<Spec, SpecExecutor> specExecutorInstanceMap = Maps.newLinkedHashMap();
    findPath(specExecutorInstanceMap, spec);
    return specExecutorInstanceMap;
  }

  /**
   * @return Transform a set of {@link TopologySpec} into a instance of {@link org.jgrapht.graph.WeightedMultigraph}
   * and filter out connections between blacklisted vertices that user specified.
   * The output of this function only stays in memory, so each time a logical flow is compiled, the multigraph will
   * be re-calculated.
   *
   */
  private void inMemoryWeightGraphGenerator() {
    for( TopologySpec topologySpec : topologySpecMap.values()) {
      weightGraphGenerateHelper(topologySpec);
    }

    // Filter out connection appearing in servicePolicy.
    servicePolicy.populateBlackListedEdges(this.weightedGraph);
    if (servicePolicy.getBlacklistedEdges().size() > 0 ) {
      for (FlowEdge toDeletedEdge : servicePolicy.getBlacklistedEdges()) {
        weightedGraph.removeEdge(toDeletedEdge);
      }
    }
  }

  // Basically a dijkstra path finding for connecting source and sink by multiple hops in between.
  // If there's any user-specified prioritization, conduct the DFS and see if the user-specified path is available.

  // there's no updates on TopologySpec, or user should be aware of the possibility
  // that a topologySpec not being reflected in findPath.
  private void findPath(Map<Spec, SpecExecutor> specExecutorInstanceMap, Spec spec) {
    inMemoryWeightGraphGenerator();
    FlowSpec flowSpec = (FlowSpec) spec;
    if (optionalUserSpecifiedPath.isPresent()) {
      log.info("Starting to evaluate user's specified path ... ");
      if (userSpecifiedPathVerificator(specExecutorInstanceMap, flowSpec)) {
        log.info("User specified path[ " + optionalUserSpecifiedPath.get() + "] successfully verified.");
        return;
      } else {
        log.error("Will not execute user specified path[ " + optionalUserSpecifiedPath.get() + "]");
        log.info("Start to execute FlowCompiler's algorithm for valid data movement path");
      }
    }

    ServiceNode sourceNode =
        new BaseServiceNodeImpl(flowSpec.getConfig().getString(ServiceConfigKeys.FLOW_SOURCE_IDENTIFIER_KEY));

    ServiceNode targetNode =
        new BaseServiceNodeImpl(flowSpec.getConfig().getString(ServiceConfigKeys.FLOW_DESTINATION_IDENTIFIER_KEY));

    List<FlowEdge> resultEdgePath = dijkstraBasedPathFindingHelper(sourceNode, targetNode, this.weightedGraph);
    for (int i = 0 ; i < resultEdgePath.size() - 1 ; i ++ ) {
      FlowEdge tmpFlowEdge = resultEdgePath.get(i);
      ServiceNode edgeSrcNode = ((LoadBasedFlowEdgeImpl)tmpFlowEdge).getSourceNode();
      ServiceNode edgeTgtNode = ((LoadBasedFlowEdgeImpl)tmpFlowEdge).getTargetNode();
      specExecutorInstanceMap.put(jobSpecGenerator(edgeSrcNode, edgeTgtNode, flowSpec),
          ((LoadBasedFlowEdgeImpl)(resultEdgePath.get(i))).getSpecExecutorInstance());
    }
  }

  /**
   * As the base implementation here, all templates will be considered for each edge.
   */
  @Override
  protected void populateEdgeTemplateMap() {
    for (FlowEdge flowEdge:this.weightedGraph.edgeSet()) {
      edgeTemplateMap.put(flowEdge.getEdgeIdentity(),
          templateCatalog.get().
              getAllTemplates().
              stream().map(jobTemplate -> jobTemplate.getUri()).collect(Collectors.toList()));
    }
  }

  // If path specified not existed, return false;
  // else return true.
  private boolean userSpecifiedPathVerificator(Map<Spec, SpecExecutor> specExecutorInstanceMap, FlowSpec flowSpec) {
    Map<Spec, SpecExecutor> tmpSpecExecutorInstanceMap = new HashMap<>();
    List<String> userSpecfiedPath = Arrays.asList(optionalUserSpecifiedPath.get().split(","));
    for (int i = 0 ; i < userSpecfiedPath.size() - 1 ; i ++ ) {
      ServiceNode sourceNode = new BaseServiceNodeImpl(userSpecfiedPath.get(i));
      ServiceNode targetNode = new BaseServiceNodeImpl(userSpecfiedPath.get(i+1));
      if (weightedGraph.containsVertex(sourceNode) && weightedGraph.containsVertex(targetNode) &&
          weightedGraph.containsEdge(sourceNode, targetNode)) {
        tmpSpecExecutorInstanceMap.put(jobSpecGenerator(sourceNode, targetNode, flowSpec),
            (((LoadBasedFlowEdgeImpl)weightedGraph.getEdge(sourceNode, targetNode)).getSpecExecutorInstance()));
      } else {
        log.error("User Specified Path is invalid");
        return false;
      }
    }
    specExecutorInstanceMap.putAll(tmpSpecExecutorInstanceMap);
    return true;
  }

  // Helper function for transform TopologySpecMap into a weightedDirectedGraph.
  private void weightGraphGenerateHelper(TopologySpec topologySpec) {
    try {
      Map<ServiceNode, ServiceNode> capabilities =
          topologySpec.getSpecExecutor().getCapabilities().get();
      for (Map.Entry<ServiceNode, ServiceNode> capability : capabilities.entrySet()) {

        BaseServiceNodeImpl sourceNode = new BaseServiceNodeImpl(capability.getKey().getNodeName());
        BaseServiceNodeImpl targetNode = new BaseServiceNodeImpl(capability.getValue().getNodeName());
        // TODO: Make it generic
        if (!weightedGraph.containsVertex(sourceNode)) {
          weightedGraph.addVertex(sourceNode);
        }
        if (!weightedGraph.containsVertex(targetNode)) {
          weightedGraph.addVertex(targetNode);
        }

        FlowEdge flowEdge = new LoadBasedFlowEdgeImpl
            (sourceNode, targetNode, defaultFlowEdgeProps, topologySpec.getSpecExecutor());

        // In Multi-Graph if flowEdge existed, just skip it.
        if (!weightedGraph.containsEdge(flowEdge)) {
          weightedGraph.addEdge(sourceNode, targetNode, flowEdge);
        }
      }
    } catch (InterruptedException | ExecutionException e) {
      Instrumented.markMeter(this.flowCompilationFailedMeter);
      throw new RuntimeException("Cannot determine topology capabilities", e);
    }
  }

  /**
   * Generate JobSpec based on the #templateURI that user specified.
   */
  private JobSpec jobSpecGenerator(ServiceNode sourceNode, ServiceNode targetNode,
      FlowEdge flowEdge, URI templateURI, FlowSpec flowSpec) {
    JobSpec jobSpec;
    JobSpec.Builder jobSpecBuilder = JobSpec.builder(jobSpecURIGenerator(flowSpec, sourceNode, targetNode))
        .withConfig(flowSpec.getConfig())
        .withDescription(flowSpec.getDescription())
        .withVersion(flowSpec.getVersion());
    if (edgeTemplateMap.containsKey(flowEdge.getEdgeIdentity())
        && edgeTemplateMap.get(flowEdge.getEdgeIdentity()).contains(templateURI)) {
      jobSpecBuilder.withTemplate(templateURI);
      try {
        jobSpec = new ResolvedJobSpec(jobSpecBuilder.build(), templateCatalog.get());
        log.info("Resolved JobSpec properties are: " + jobSpec.getConfigAsProperties());
      } catch (SpecNotFoundException | JobTemplate.TemplateException e) {
        throw new RuntimeException("Could not resolve template in JobSpec from TemplateCatalog", e);
      }
    } else {
      jobSpec = jobSpecBuilder.build();
      log.info("Unresolved JobSpec properties are: " + jobSpec.getConfigAsProperties());
    }
    return jobSpec;
  }

  /**
   * A naive implementation of resolving templates in each JobSpec among Multi-hop FlowSpec.
   * Handle the case when edge is not specified.
   * Always select the first available template.
   */
  private JobSpec jobSpecGenerator(ServiceNode sourceNode, ServiceNode targetNode, FlowSpec flowSpec) {
    FlowEdge flowEdge = weightedGraph.getAllEdges(sourceNode, targetNode).iterator().next();
    URI firstTemplateURI = (edgeTemplateMap!=null && edgeTemplateMap.containsKey(flowEdge.getEdgeIdentity())) ?
        edgeTemplateMap.get(flowEdge.getEdgeIdentity()).get(0)
        : jobSpecGenerator(flowSpec).getUri();
    return this.jobSpecGenerator(sourceNode, targetNode, flowEdge, firstTemplateURI, flowSpec);
  }

  /**
   * A naive implementation of generating a jobSpec's URI within a multi-hop logical Flow.
   */
  public static URI jobSpecURIGenerator(FlowSpec flowSpec, ServiceNode sourceNode, ServiceNode targetNode) {
    try {
      return new URI(flowSpec.getUri().getScheme(), flowSpec.getUri().getAuthority(),
          "/" + sourceNode.getNodeName() + "-" + targetNode.getNodeName(), null);
    } catch (URISyntaxException e) {
      log.error("URI construction failed when jobSpec from " + sourceNode.getNodeName() + " to " + targetNode.getNodeName());
      throw new RuntimeException();
    }
  }
}