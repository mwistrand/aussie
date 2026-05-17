package aussie.e2e.support;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Marks the suite as failed when any individual test fails or any container
 * fixture aborts. Used by {@link SuiteBootstrapListener} to decide whether to
 * dump container logs at teardown.
 */
final class FailureTracker implements TestExecutionListener {

    private final Runnable onFailure;

    FailureTracker(Runnable onFailure) {
        this.onFailure = onFailure;
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            onFailure.run();
        }
    }
}
