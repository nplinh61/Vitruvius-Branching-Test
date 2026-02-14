package tools.vitruv.methodologisttemplate.vsum;

import mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.propagation.ChangePropagationMode;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.branch.data.ValidationResult;
import tools.vitruv.framework.vsum.branch.handler.VsumReloadWatcher;
import tools.vitruv.framework.vsum.branch.handler.VsumValidationWatcher;
import tools.vitruv.framework.vsum.branch.util.ReloadTriggerFile;
import tools.vitruv.framework.vsum.branch.util.ValidationResultFile;
import tools.vitruv.framework.vsum.branch.util.ValidationTriggerFile;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.System;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Day 3 Vitruvius Git branching features:
 * pre-commit validation, changelog generation, and the trigger/result
 * inter-process communication channel.
 *
 * <p>Each test uses a real {@link InternalVirtualModel} backed by a real JGit repository
 * and real {@code .xmi} model files in a temporary directory, identical to the approach
 * in {@link BranchSwitchingIntegrationTest} and {@link GitCheckoutIntegrationTest}.
 *
 * <p>Because JGit does not execute hook scripts during API calls, all tests that exercise
 * the trigger-file flow create the trigger file manually to simulate what the real hook
 * would do on the command line.
 *
 * <p>Flows covered:
 * <ul>
 *   <li>Pre-commit validation: manually created trigger → watcher validates real VSUM
 *       → result files written → result readable from JSON.</li>
 *   <li>Reload on branch switch: manually created reload trigger → reload watcher detects
 *       it → trigger file deleted → fresh view is obtainable.</li>
 *   <li>Changelog generation: passing validation → changelog file written under
 *       {@code .vitruvius/changelogs/}.</li>
 *   <li>Trigger/result round-trip: all four {@link ValidationResult} outcomes survive
 *       real disk serialization and deserialization.</li>
 * </ul>
 */
@DisplayName("Day 3 integration tests: pre-commit validation and changelog")
class PreCommitValidationIntegrationTest {

    private static final String COMMIT_SHA  = "abc1234567890abcdef1234567890abcdef12345";
    private static final String BRANCH_MAIN = "main";
    private static final String BRANCH_FEAT = "feature-validation";

    @TempDir
    Path tempDir;

    /** Real JGit repository initialized in the temp directory. */
    private Git git;

    /** Real Vitruvius VSUM backed by the temp directory. */
    private InternalVirtualModel vsum;

    @BeforeAll
    static void registerResourceFactory() {
        // required for EMF to serialize and deserialize .xmi model files correctly.
        // without this registration, resource creation returns null and commitChanges
        // throws a NullPointerException inside DefaultStateBasedChangeResolutionStrategy.
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());
    }

    @BeforeEach
    void setUp() throws Exception {
        // initialize a real Git repository so watcher path resolution works correctly.
        git = Git.init().setDirectory(tempDir.toFile()).call();

        vsum = createVirtualModel(tempDir);

        // populate the model with an initial system so validation has real resources to check.
        addSystem(vsum, tempDir);
    }

    @AfterEach
    void tearDown() {
        if (git != null) {
            git.close();
        }
    }

    // -------------------------------------------------------------------------
    // flow 1: pre-commit validation
    // -------------------------------------------------------------------------

    /**
     * Verifies the end-to-end pre-commit validation flow with a real VSUM.
     *
     * <p>The hook is simulated by manually writing the trigger file. The watcher picks it up,
     * validates the real virtual model (which contains a well-formed System/Component model),
     * and writes result files. The test then reads the result back from disk and verifies
     * that the commit would have been allowed.
     */
    @Test
    @DisplayName("Real VSUM with a valid model passes pre-commit validation")
    void realVsumWithValidModelPassesValidation() throws Exception {
        var triggerFile = new ValidationTriggerFile(tempDir);
        var resultFile  = new ValidationResultFile(tempDir);
        var watcher     = new VsumValidationWatcher(vsum, tempDir);

        watcher.start();
        try {
            // JGit does not run hooks, so the trigger is written manually to simulate the
            // pre-commit hook creating it just before the commit is allowed or blocked.
            String requestId = triggerFile.createTrigger(COMMIT_SHA, BRANCH_MAIN);
            waitForBothResultFiles(resultFile, requestId);

            // the trigger file must be consumed so the watcher does not validate again.
            assertFalse(triggerFile.exists(),
                    "trigger file must be deleted after the watcher processes it");

            // read the result from disk exactly as the hook script would.
            ValidationResult result = resultFile.readResult(requestId);
            assertNotNull(result, "a result file must be present after validation");
            assertTrue(result.isValid(),
                    "a well-formed System/Component model must pass VSUM validation");

            // the text file must contain the passed indicator for terminal display.
            String textContent = Files.readString(resultFile.getTextResultPath(requestId));
            assertTrue(textContent.contains("PASSED") || textContent.contains("✔"),
                    "text result must indicate a passed validation");

        } finally {
            watcher.stop();
        }
    }

    /**
     * Verifies that the watcher does not validate when no trigger file has been created.
     * The real VSUM must not be disturbed by idle poll cycles.
     */
    @Test
    @DisplayName("No validation is triggered when no pre-commit trigger file exists")
    void noValidationWithoutTriggerFile() throws Exception {
        var resultFile = new ValidationResultFile(tempDir);
        var watcher    = new VsumValidationWatcher(vsum, tempDir);

        watcher.start();
        try {
            // wait for several poll cycles with no trigger written.
            Thread.sleep(1500);

            // no result files should have appeared since no trigger was created.
            assertFalse(resultFile.exists(COMMIT_SHA),
                    "no result files must appear when no trigger file was created");
        } finally {
            watcher.stop();
        }
    }

    /**
     * Verifies that two sequential commits, each simulated by writing a trigger file
     * manually, each receive their own independent validation result. This confirms that
     * the request-identifier isolation works correctly with a real VSUM.
     */
    @Test
    @DisplayName("Two sequential pre-commit triggers each produce an independent validation result")
    void twoSequentialCommitsProduceIndependentResults() throws Exception {
        var triggerFile = new ValidationTriggerFile(tempDir);
        var resultFile  = new ValidationResultFile(tempDir);
        var watcher     = new VsumValidationWatcher(vsum, tempDir);

        watcher.start();
        try {
            // first commit: simulate trigger and wait for result.
            String requestId1 = triggerFile.createTrigger(COMMIT_SHA, BRANCH_MAIN);
            waitForBothResultFiles(resultFile, requestId1);
            assertTrue(resultFile.exists(requestId1), "first commit must have a result file");
            resultFile.deleteResult(requestId1);

            // second commit: simulate trigger and wait for result.
            String requestId2 = triggerFile.createTrigger(COMMIT_SHA, BRANCH_FEAT);
            waitForBothResultFiles(resultFile, requestId2);
            assertTrue(resultFile.exists(requestId2), "second commit must have a result file");

            // the identifiers must differ so the hook cannot confuse the two results.
            assertNotEquals(requestId1, requestId2,
                    "each simulated commit must receive a unique request identifier");
            assertTrue(resultFile.readResult(requestId2).isValid(),
                    "second validation must also pass for a well-formed model");

        } finally {
            watcher.stop();
        }
    }

    // -------------------------------------------------------------------------
    // flow 2: branch switch reload with real VSUM
    // -------------------------------------------------------------------------

    /**
     * Verifies that manually writing a reload trigger causes the reload watcher to consume
     * the trigger file. Because reload is fire-and-forget, the test confirms the trigger
     * file was deleted — which means the watcher processed it — and that a fresh view is
     * still obtainable from the VSUM afterwards.
     */
    @Test
    @DisplayName("Manual reload trigger is consumed by the reload watcher")
    void manualReloadTriggerIsConsumedByWatcher() throws Exception {
        var reloadTrigger = new ReloadTriggerFile(tempDir);
        var reloadWatcher = new VsumReloadWatcher(vsum, tempDir);

        reloadWatcher.start();
        try {
            // JGit does not run hooks, so the trigger is written manually to simulate
            // what the post-checkout hook script would do after a git checkout.
            reloadTrigger.createTrigger(BRANCH_FEAT);

            // wait for the watcher to consume the trigger file.
            waitUntil(() -> !reloadTrigger.exists(), 2000);

            assertFalse(reloadTrigger.exists(),
                    "reload trigger file must be deleted after the watcher processes it");

            // after a reload the VSUM state is refreshed; a fresh view must be obtainable.
            // stale view references are not updated after reload, so a new one must be created.
            CommittableView freshView = getDefaultView(vsum);
            assertNotNull(freshView,
                    "a fresh view must be obtainable from the reloaded VSUM");

        } finally {
            reloadWatcher.stop();
        }
    }

    // -------------------------------------------------------------------------
    // flow 3: changelog generation
    // -------------------------------------------------------------------------

    /**
     * Verifies that a semantic changelog file is written under
     * {@code .vitruvius/changelogs/<shortSha>.txt} when a validation trigger is processed
     * and the real VSUM passes validation.
     */
    @Test
    @DisplayName("Changelog file is written under .vitruvius/changelogs/ after a passing validation")
    void passingValidationWritesChangelogFile() throws Exception {
        var triggerFile = new ValidationTriggerFile(tempDir);
        var resultFile  = new ValidationResultFile(tempDir);
        var watcher     = new VsumValidationWatcher(vsum, tempDir);

        watcher.start();
        try {
            String requestId = triggerFile.createTrigger(COMMIT_SHA, BRANCH_MAIN);
            waitForBothResultFiles(resultFile, requestId);

            // confirm validation passed before checking for the changelog.
            assertTrue(resultFile.readResult(requestId).isValid(),
                    "validation must pass before checking for the changelog file");

            // the changelog is written to .vitruvius/changelogs/<7-char SHA>.txt.
            String shortSha      = COMMIT_SHA.substring(0, 7);
            Path   changelogPath = tempDir.resolve(".vitruvius")
                    .resolve("changelogs")
                    .resolve(shortSha + ".txt");

            // the changelog write happens after the result files; give it a moment.
            waitUntilFileExists(changelogPath, 2000);

            assertTrue(Files.exists(changelogPath),
                    "changelog must be written at .vitruvius/changelogs/" + shortSha + ".txt");

            String content = Files.readString(changelogPath);
            assertTrue(content.contains(COMMIT_SHA), "changelog must contain the full commit SHA");
            assertTrue(content.contains(BRANCH_MAIN), "changelog must contain the branch name");

        } finally {
            watcher.stop();
        }
    }

    // -------------------------------------------------------------------------
    // flow 4: trigger/result round-trip through real disk files
    // -------------------------------------------------------------------------

    /**
     * Verifies that all four {@link ValidationResult} factory method outcomes survive a
     * complete round-trip through real files on disk without losing any data.
     *
     * <p>This includes the previously fixed {@code failureWithWarnings} case where warnings
     * were silently dropped on deserialization.
     */
    @Test
    @DisplayName("All four ValidationResult outcomes survive a full round-trip through disk files")
    void allValidationOutcomesSurviveDiskRoundTrip() throws Exception {
        var resultFile = new ValidationResultFile(tempDir);

        // success: valid, no errors, no warnings.
        resultFile.writeResult(ValidationResult.success(), "req-success");
        var success = resultFile.readResult("req-success");
        assertNotNull(success);
        assertTrue(success.isValid());
        assertFalse(success.hasErrors());
        assertFalse(success.hasWarnings());

        // success with warnings: valid, warnings present.
        resultFile.writeResult(
                ValidationResult.successWithWarnings(List.of("No resources in VSUM")),
                "req-warn");
        var warn = resultFile.readResult("req-warn");
        assertNotNull(warn);
        assertTrue(warn.isValid());
        assertTrue(warn.hasWarnings());
        assertEquals("No resources in VSUM", warn.getWarnings().get(0));

        // failure: invalid, errors present, no warnings.
        resultFile.writeResult(
                ValidationResult.failure(List.of("Unresolved proxy in example.model")),
                "req-fail");
        var fail = resultFile.readResult("req-fail");
        assertNotNull(fail);
        assertFalse(fail.isValid());
        assertEquals("Unresolved proxy in example.model", fail.getErrors().get(0));
        assertFalse(fail.hasWarnings(),
                "a failure result without warnings must not gain warnings after round-trip");

        // failure with warnings: invalid, both errors and warnings present.
        resultFile.writeResult(
                ValidationResult.failureWithWarnings(
                        List.of("Null correspondence model"),
                        List.of("Large model detected")),
                "req-fail-warn");
        var failWarn = resultFile.readResult("req-fail-warn");
        assertNotNull(failWarn);
        assertFalse(failWarn.isValid());
        assertTrue(failWarn.hasErrors());
        // warnings must survive the round-trip (regression guard for the deserialization fix).
        assertTrue(failWarn.hasWarnings(),
                "warnings in a failureWithWarnings result must be preserved through disk round-trip");
        assertEquals("Large model detected", failWarn.getWarnings().get(0));
    }

    /**
     * Verifies the request-identifier contract: the identifier returned by
     * {@link ValidationTriggerFile#createTrigger} must match the identifier read back by
     * {@link ValidationTriggerFile#checkAndClearTrigger}, and must correctly locate the
     * corresponding result file written by {@link ValidationResultFile#writeResult}.
     */
    @Test
    @DisplayName("Request identifier correctly links a trigger to its result file")
    void requestIdentifierLinksTriggersToResults() throws Exception {
        var triggerFile = new ValidationTriggerFile(tempDir);
        var resultFile  = new ValidationResultFile(tempDir);

        // the hook creates the trigger and stores the returned identifier.
        String requestId = triggerFile.createTrigger(COMMIT_SHA, BRANCH_MAIN);

        // the watcher reads the trigger and must receive the same identifier.
        var info = triggerFile.checkAndClearTrigger();
        assertNotNull(info);
        assertEquals(requestId, info.getRequestId(),
                "the identifier from createTrigger must match what checkAndClearTrigger returns");
        assertEquals(COMMIT_SHA, info.getCommitSha());
        assertEquals(BRANCH_MAIN, info.getBranch());

        // the watcher writes the result using the identifier from the trigger info.
        resultFile.writeResult(ValidationResult.success(), info.getRequestId());

        // the hook uses the identifier it stored at trigger creation time to find the result.
        assertTrue(resultFile.exists(requestId),
                "the result file must be locatable using the identifier returned by createTrigger");
        assertTrue(resultFile.readResult(requestId).isValid());
    }

    // -------------------------------------------------------------------------
    // helpers — matching the exact patterns from VSUMExampleTest
    // -------------------------------------------------------------------------

    /**
     * Creates a real {@link InternalVirtualModel} with the System/Component metamodel,
     * consistency rules, and transitive cyclic change propagation — exactly as in
     * .
     */
    private InternalVirtualModel createVirtualModel(Path projectPath) throws IOException {
        InternalVirtualModel model = new VirtualModelBuilder()
                .withStorageFolder(projectPath)
                .withUserInteractorForResultProvider(
                        new TestUserInteraction.ResultProvider(new TestUserInteraction()))
                .withChangePropagationSpecifications(
                        new Model2Model2ChangePropagationSpecification())
                .buildAndInitialize();
        model.setChangePropagationMode(ChangePropagationMode.TRANSITIVE_CYCLIC);
        return model;
    }

    /**
     * Populates the VSUM with an empty {@code System} root registered at
     * {@code example.model}. Matches the {@code addSystem} helper in {@link VSUMExampleTest}.
     */
    private void addSystem(InternalVirtualModel model, Path projectPath) {
        modifyView(getDefaultView(model), view ->
                view.registerRoot(
                        ModelFactory.eINSTANCE.createSystem(),
                        URI.createFileURI(projectPath.toString() + "/example.model")));
    }

    /**
     * Returns a view selecting all {@code System} root objects from the VSUM.
     * Must be called again after a reload — stale view references are not updated
     * automatically after {@code reload()}.
     */
    private CommittableView getDefaultView(InternalVirtualModel model) {
        var selector = model.createSelector(
                ViewTypeFactory.createIdentityMappingViewType("default"));
        selector.getSelectableElements().stream()
                .filter(element -> element instanceof System)
                .forEach(element -> selector.setSelected(element, true));
        return selector.createView().withChangeDerivingTrait();
    }

    /**
     * Applies the given modification to a view and commits the changes.
     * Matches the {@code modifyView} helper in {@link VSUMExampleTest}.
     */
    private void modifyView(CommittableView view, Consumer<CommittableView> modification) {
        modification.accept(view);
        view.commitChanges();
    }

    /**
     * Polls until both the text and JSON result files exist for the given request identifier,
     * or until the 2-second timeout expires. Both files must be present before the test
     * reads the JSON to avoid a race condition on the write.
     */
    private static void waitForBothResultFiles(ValidationResultFile resultFile, String requestId)
            throws InterruptedException {
        long deadline = java.lang.System.currentTimeMillis() + 2000;
        while (java.lang.System.currentTimeMillis() < deadline) {
            if (Files.exists(resultFile.getTextResultPath(requestId))
                    && Files.exists(resultFile.getJsonResultPath(requestId))) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /**
     * Polls until the given condition becomes true, or until the timeout expires.
     */
    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = java.lang.System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && java.lang.System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    /**
     * Polls until the given file exists on disk, or until the timeout expires.
     */
    private static void waitUntilFileExists(Path path, long timeoutMs)
            throws InterruptedException {
        long deadline = java.lang.System.currentTimeMillis() + timeoutMs;
        while (!Files.exists(path) && java.lang.System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}