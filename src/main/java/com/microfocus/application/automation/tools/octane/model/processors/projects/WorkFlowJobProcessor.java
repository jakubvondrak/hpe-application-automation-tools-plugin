/*
 * Certain versions of software and/or documents ("Material") accessible here may contain branding from
 * Hewlett-Packard Company (now HP Inc.) and Hewlett Packard Enterprise Company.  As of September 1, 2017,
 * the Material is now offered by Micro Focus, a separately owned and operated company.  Any reference to the HP
 * and Hewlett Packard Enterprise/HPE marks is historical in nature, and the HP and Hewlett Packard Enterprise/HPE
 * marks are the property of their respective owners.
 * __________________________________________________________________
 * MIT License
 *
 * (c) Copyright 2012-2021 Micro Focus or one of its affiliates.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * ___________________________________________________________________
 */

package com.microfocus.application.automation.tools.octane.model.processors.projects;

import com.microfocus.application.automation.tools.octane.configuration.SDKBasedLoggerProvider;
import com.microfocus.application.automation.tools.octane.tests.build.BuildHandlerUtils;
import hudson.model.*;
import org.apache.logging.log4j.Logger;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/**
 * Created with IntelliJ IDEA.
 * User: gullery
 * Date: 24/12/14
 * Time: 13:40
 * To change this template use File | Settings | File Templates.
 */

public class WorkFlowJobProcessor extends AbstractProjectProcessor<WorkflowJob> {
	private static final Logger logger = SDKBasedLoggerProvider.getLogger(WorkFlowJobProcessor.class);
	WorkFlowJobProcessor(Job job) {
		super((WorkflowJob) job);
	}

	public void scheduleBuild(Cause cause, ParametersAction parametersAction) {
		int delay = this.job.getQuietPeriod();
		CauseAction causeAction = new CauseAction(cause);
		this.job.scheduleBuild2(delay, parametersAction, causeAction);
	}

	@Override
	public String getTranslatedJobName() {
		if (JobProcessorFactory.WORKFLOW_MULTI_BRANCH_JOB_NAME.equals(job.getParent().getClass().getName())) {
			return BuildHandlerUtils.translateFolderJobName(job.getFullName());
		} else {
			return super.getTranslatedJobName();
		}
	}

	protected void stopBuild(Run run) {
		WorkflowRun aBuild = (WorkflowRun)run;
		try {
			aBuild.doStop();
			logger.info("Build is stopped : " + aBuild.getParent().getDisplayName() + aBuild.getDisplayName());
		} catch (Exception e) {
			logger.warn("Failed to stop build '" + aBuild.getDisplayName() + "' :" + e.getMessage(), e);
		}
	}
}
