package org.jboss.qa.maven;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component(role = EventSpy.class)
public class FailureHandlerJunit extends AbstractEventSpy {

    @Requirement
    private Logger logger;

    @Override
    public void init(Context context) throws Exception {
        logger.info("Maven failure handler junit extension loaded.");
        super.init(context);
    }

    @Override
    public void onEvent(Object event) throws Exception {
        ExecutionEvent executionEvent = null;
        String baseDirectory = ".";
        if (event instanceof DefaultMavenExecutionRequest) {
            DefaultMavenExecutionRequest defaultMavenExecutionRequest = (DefaultMavenExecutionRequest) event;
            baseDirectory = defaultMavenExecutionRequest.getBaseDirectory();
        }
        // use base directory for exceptions from main maven project (not submodules)
        String targetDirectory = new File(baseDirectory, "target").getAbsolutePath();
        if (event instanceof MavenExecutionResult) {
            MavenExecutionResult result = ((MavenExecutionResult) event);
            for (Throwable throwable : result.getExceptions()) {
                // Another interesting classes to consider are: ProjectBuildingException, DependencyResolutionException
                // But we are using more general approach per lifecycle execution
                String[] groupArtifactIds = new String[2];
                if (throwable instanceof LifecycleExecutionException) {
                    LifecycleExecutionException lifecycleExecutionException = (LifecycleExecutionException) throwable;
                    // if the exception occurred in maven module, it should be in lifecycleExecutionException.getProject()
                    if (lifecycleExecutionException.getProject() != null && lifecycleExecutionException.getProject().getBuild() != null && lifecycleExecutionException.getProject().getBuild().getDirectory() != null) {
                        targetDirectory = lifecycleExecutionException.getProject().getBuild().getDirectory();
                    }
                    setGroupArtifactIds(lifecycleExecutionException.getProject(), groupArtifactIds);
                }
                // if the exception didn't occur in module, set it for the main project
                setGroupArtifactIds(result.getProject(), groupArtifactIds);
                createJunitXml(groupArtifactIds[0] == null ? "unknown" : groupArtifactIds[0], groupArtifactIds[1] == null ? "unknown" : groupArtifactIds[1], targetDirectory, throwable);
            }
        }
        // catching project failure event
        if (event instanceof ExecutionEvent &&
                (executionEvent = (ExecutionEvent) event).getType() == ExecutionEvent.Type.ProjectFailed) {
            final MavenProject project = executionEvent.getSession().getCurrentProject();
            createJunitXml(project.getGroupId(), project.getArtifactId(), project.getModel().getBuild().getDirectory(), executionEvent.getException());
        }
    }

    private void setGroupArtifactIds(MavenProject project, String[] groupArtifactIds) {
        if (project != null) {
            if (groupArtifactIds[0] == null) {
                groupArtifactIds[0] = nullIfEmpty(project.getGroupId());
            }
            if (groupArtifactIds[1] == null) {
                groupArtifactIds[1] = nullIfEmpty(project.getArtifactId());
            }
        }
    }

    private String nullIfEmpty(String content) {
        return content == null || "".equals(content) ? null : content;
    }

    private void createJunitXml(String groupId, String artifactId, String targetDirectory, Throwable exception) {
        final File surefireReportsFile = new File(targetDirectory, "surefire-reports");
        final File failsafeReportsFile = new File(targetDirectory, "failsafe-reports");
        if (!surefireReportsFile.exists() && !failsafeReportsFile.exists()) {
            surefireReportsFile.mkdirs();
            createJunitXml(groupId, artifactId,
                    ExceptionUtils.getRootCause(exception).getClass().getName(),
                    exception.getMessage(), ExceptionUtils.getStackTrace(exception), surefireReportsFile);
        }
    }

    private void createJunitXml(String groupId, String artifactId, String errorType, String errorMessage,
            String errorStacktrace, File folder) {
        try {
            // Allowed chars in XML are: #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
            // Regex removes illegal ASCII control chars:
            errorMessage = errorMessage.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
            errorStacktrace = errorStacktrace.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
            final String fullName = groupId + ".modules." + artifactId;
            final DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            final DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
            final Document doc = docBuilder.newDocument();
            final Element rootElement = doc.createElement("testsuite");
            rootElement.setAttribute("name", fullName);
            rootElement.setAttribute("tests", "1");
            rootElement.setAttribute("errors", "1");
            rootElement.setAttribute("skipped", "0");
            rootElement.setAttribute("failures", "0");
            doc.appendChild(rootElement);
            final Element testcase = doc.createElement("testcase");
            testcase.setAttribute("classname", fullName);
            testcase.setAttribute("name", "failedMavenPhase");
            final Element error = doc.createElement("error");
            error.setAttribute("message", errorMessage);
            error.setAttribute("type", errorType);
            error.appendChild(doc.createTextNode(errorStacktrace));
            testcase.appendChild(error);
            rootElement.appendChild(testcase);

            final TransformerFactory transformerFactory = TransformerFactory.newInstance();
            final Transformer transformer = transformerFactory.newTransformer();
            final DOMSource source = new DOMSource(doc);
            final StreamResult result = new StreamResult(
                    new File(folder, String.format("TEST-%s.xml", fullName)));
            transformer.transform(source, result);
        } catch (Exception exception) {
            logger.error("Failed to create XML report.", exception);
        }
    }
}
