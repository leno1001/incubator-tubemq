/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tubemq.agent.core.job;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.tubemq.agent.conf.AgentConfiguration;
import org.apache.tubemq.agent.constants.AgentConstants;
import org.apache.tubemq.agent.core.AgentManager;
import org.apache.tubemq.agent.core.task.Task;
import org.apache.tubemq.agent.core.task.TaskManager;
import org.apache.tubemq.agent.state.AbstractStateWrapper;
import org.apache.tubemq.agent.state.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JobWrapper is used in JobManager, it defines the life cycle of
 * running job and maintains the state of job.
 */
public class JobWrapper extends AbstractStateWrapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobWrapper.class);

    private final AgentConfiguration agentConf;
    private final TaskManager taskManager;
    private final JobManager jobManager;
    private final Job job;

    private final List<Task> allTasks;

    public JobWrapper(AgentManager manager, Job job) {
        super();
        this.agentConf = AgentConfiguration.getAgentConf();
        this.taskManager = manager.getTaskManager();
        this.jobManager = manager.getJobManager();
        this.job = job;
        this.allTasks = new ArrayList<>();
        doChangeState(State.ACCEPTED);
    }

    /**
     * check states of all tasks, wait if one of them not finished.
     */
    private void checkAllTasksStateAndWait() throws Exception {
        boolean isFinished;

        long checkInterval = agentConf.getLong(
                AgentConstants.JOB_FINISH_CHECK_INTERVAL, AgentConstants.DEFAULT_JOB_FINISH_CHECK_INTERVAL);
        do {
            // check whether all tasks have finished.
            isFinished = allTasks.stream().allMatch(task ->
                    taskManager.isTaskFinished(task.getTaskId()));
            TimeUnit.SECONDS.sleep(checkInterval);
        } while (!isFinished);
        LOGGER.info("all tasks of {} has been checked", job.getJobId());
        doChangeState(State.SUCCEEDED);
    }

    /**
     * submit all tasks
     */
    private void submitAllTasks() {
        List<Task> tasks = job.createTasks();
        tasks.forEach(task -> {
            allTasks.add(task);
            taskManager.submitTask(task);
        });
    }

    /**
     * get job
     * @return job
     */
    public Job getJob() {
        return job;
    }

    /**
     * cleanup job
     */
    private void cleanup() {
        allTasks.forEach(task -> taskManager.removeTask(task.getTaskId()));
    }

    @Override
    public void run() {
        try {
            doChangeState(State.RUNNING);
            submitAllTasks();
            checkAllTasksStateAndWait();
            cleanup();
        } catch (Exception ex) {
            doChangeState(State.FAILED);
            LOGGER.error("error caught: {}, message: {}",
                    job.getJobConf().toJsonStr(), ex.getMessage());
        }
    }

    @Override
    public void addCallbacks() {
        this.addCallback(State.ACCEPTED, State.RUNNING, (before, after) -> {

        }).addCallback(State.RUNNING, State.FAILED, (before, after) -> {

        }).addCallback(State.RUNNING, State.SUCCEEDED, ((before, after) -> {
            jobManager.markJobAsSuccess(job.getJobId());
        }));
    }
}