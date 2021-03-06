/*
 * Copyright 2016 Google.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.genomics.dockerflow.runner;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.PipelineResult;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineOptions;
import com.google.cloud.genomics.dockerflow.DockerflowConstants;
import com.google.cloud.genomics.dockerflow.args.TaskArgs;
import com.google.cloud.genomics.dockerflow.args.WorkflowArgs;
import com.google.cloud.genomics.dockerflow.dataflow.DataflowFactory;
import com.google.cloud.genomics.dockerflow.task.Task;
import com.google.cloud.genomics.dockerflow.task.TaskDefn;
import com.google.cloud.genomics.dockerflow.task.TaskDefn.Param;
import com.google.cloud.genomics.dockerflow.util.HttpUtils;
import com.google.cloud.genomics.dockerflow.util.StringUtils;
import com.google.cloud.genomics.dockerflow.workflow.Workflow;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for running workflows (directed acyclic graphs) of Docker steps with Dataflow.
 * Execution of Docker steps happens through the Pipelines API.
 */
public class TaskRunner implements DockerflowConstants {
  private static final Logger LOG = LoggerFactory.getLogger(TaskRunner.class);

  /** Run a Docker workflow on Dataflow. */
  public static void run(Workflow w, Map<String, WorkflowArgs> a, DataflowPipelineOptions o)
      throws IOException {
    LOG.info("Running workflow graph");
    if (w.getArgs().getProjectId() == null) {
      throw new IllegalArgumentException("Project id is required");
    }

    Pipeline p = DataflowFactory.dataflow(w, a, o);

    LOG.info("Created Dataflow pipeline");
    LOG.debug(w.toString());

    PipelineResult r = p.run();

    LOG.info("Dataflow pipeline completed");
    LOG.info("Result state: " + r.getState());
  }

  /**
   * Run a single task. If running in test mode, no API call will be made.
   *
   * @param w the task definition as a workflow object
   * @return the operation status
   * @throws IOException
   */
  public static Operation runTask(Task t) throws IOException {
    Operation o;
    LOG.info("Pipelines API request: " + StringUtils.toJson(getRequest(t)));

    if (t.getArgs() != null
        && t.getArgs() instanceof WorkflowArgs
        && ((WorkflowArgs) t.getArgs()).isTesting() != null
        && ((WorkflowArgs) t.getArgs()).isTesting()) {
      LOG.info("Running in test mode. No API call will be made. Name=" + t.getDefn().getName());
      o = new Operation();
      o.setDone(true);
      o.setName("operations/TEST-" + t.hashCode());
    } else {
      o = callAsyncWebService(getRequest(t));
    }
    return o;
  }

  /**
   * Get the web service request object for this task.
   *
   * @param t the enclosing task
   * @return a json-serializable request object
   */
  public static Object getRequest(Task t) {
    TaskRequest r = new TaskRequest();

    // Remove CWL fields from the request: Pipelines API doesn't recognize them
    TaskDefn defn = new TaskDefn(t.getDefn());
    if (defn.getInputParameters() != null) {
      for (Param p : defn.getInputParameters()) {
        p.setInputBinding(null); // exclude from json
        p.setType(null);
      }
    }
    if (defn.getOutputParameters() != null) {
      for (Param p : defn.getOutputParameters()) {
        p.setInputBinding(null); // exclude from json
        p.setType(null);
      }
    }
    r.setEphemeralPipeline(defn);

    // Remove other fields not recognized by Pipelines API
    TaskArgs ta = new TaskArgs(t.getArgs());
    ta.setFromFile(null);
    r.setPipelineArgs(ta);
    
    return r;
  }
  
  /**
   * A replacement for the autogenerated Pipelines API's RunPipelineRequest object. Reason: it works
   * with standard json serializers.
   */
  @SuppressWarnings("serial")
  public static class TaskRequest implements Serializable {
    private String pipelineId;
    private TaskDefn ephemeralPipeline;
    private TaskArgs pipelineArgs;

    public String getPipelineId() {
      return pipelineId;
    }

    public void setPipelineId(String pipelineId) {
      this.pipelineId = pipelineId;
    }

    public TaskDefn getEphemeralPipeline() {
      return ephemeralPipeline;
    }

    public void setEphemeralPipeline(TaskDefn ephemeralPipeline) {
      this.ephemeralPipeline = ephemeralPipeline;
    }

    public TaskArgs getPipelineArgs() {
      return pipelineArgs;
    }

    public void setPipelineArgs(TaskArgs pipelineArgs) {
      this.pipelineArgs = pipelineArgs;
    }
  }

  /**
   * Submit a web service task asynchronously.
   *
   * @param req the task definition as a PipelineAPI request object
   * @return the operation status
   * @throws IOException
   * @throws TaskException if operation returned errors
   */
  public static Operation callAsyncWebService(Object req) throws IOException, TaskException {
    LOG.info("Call Pipelines API.");
    String res = HttpUtils.doPost(API_RUN_PIPELINE, req);
    Operation status = StringUtils.fromJson(res, Operation.class);

    LOG.info("operationId=" + status.getName());

    if (status.getError() != null) {
      String msg = "Failed to call web service!" + status.getError().getMessage();
      if (status.getError().getDetails() != null) {
        msg += "\n" + status.getError().getDetails();
      }
      LOG.error(msg);
      throw new TaskException(msg);

    } else if (status.getResponse() != null) {
      LOG.info("Submitted: " + status.getResponse().toString());
    } else {
      LOG.info("Submitted");
    }

    return status;
  }

  /**
   * Block until the operation is done.
   *
   * @param operationId the operation to poll
   * @return final status
   */
  public static Operation wait(String operationId) throws IOException {
    Operation o = new Operation();
    o.setName(operationId);
    o.setDone(false);
    return wait(o);
  }

  /**
   * Block until the operation is done.
   *
   * @param op the operation to poll
   * @return final status
   */
  public static Operation wait(Operation op) throws IOException {
    if (op.getDone()) {
      return op;
    }

    Operation status = op;
    do {
      LOG.debug("Sleeping for " + POLL_INTERVAL + " sec");
      try {
        TimeUnit.SECONDS.sleep(POLL_INTERVAL);
      } catch (InterruptedException e) {
        // ignore
      }
      try {
        status = checkStatus(status.getName());
      } catch (IOException e) {
        LOG.warn("Error checking operation status: " + e.getMessage());
      }
    } while (status.getDone() == null || !status.getDone());

    LOG.info("Done! " + status.getName());
    return status;
  }

  /**
   * Check status by calling the operations REST API.
   *
   * @param operationId the operation to check
   * @return the updated status
   */
  public static Operation checkStatus(String operationId) throws IOException {
    String res = HttpUtils.doGet(API_OPERATIONS + operationId);
    Operation status = StringUtils.fromJson(res, Operation.class);
    return status;
  }
}
