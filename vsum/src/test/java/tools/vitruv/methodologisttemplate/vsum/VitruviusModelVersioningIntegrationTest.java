package tools.vitruv.methodologisttemplate.vsum;

import mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.propagation.ChangePropagationMode;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.branch.data.ValidationResult;
import tools.vitruv.framework.vsum.branch.handler.VsumMergeWatcher;
import tools.vitruv.framework.vsum.branch.handler.VsumPostCommitWatcher;
import tools.vitruv.framework.vsum.branch.handler.VsumReloadWatcher;
import tools.vitruv.framework.vsum.branch.handler.VsumValidationWatcher;
import tools.vitruv.framework.vsum.branch.util.*;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.System;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the complete Vitruvius model versioning workflow.
 *
 * <p>This class contains a single test that walks through the full developer experience
 * in one sequential scenario, exactly as it would happen on the command line:
 *
 * <ol>
 *   <li>Vitruvius starts, installs both Git hooks, and launches both background watchers.</li>
 *   <li>Developer works on main: makes a model change and commits -> pre-commit hook fires
 *       -> VSUM validated -> commit allowed -> changelog written.</li>
 *   <li>Developer switches to a feature branch -> post-checkout hook fires -> VSUM reloads.</li>
 *   <li>Developer makes a model change on the feature branch and commits -> pre-commit hook
 *       fires -> VSUM validated -> commit allowed -> second changelog written.</li>
 *   <li>Developer switches back to main -> post-checkout hook fires -> VSUM reloads again.</li>
 *   <li>Developer makes a final commit on main -> pre-commit hook fires -> VSUM validated
 *       -> commit allowed -> third changelog written.</li>
 * </ol>
 *
 * <p>The one deliberate simplification throughout is that JGit does not execute shell
 * scripts during API calls. Trigger files are therefore written manually in each step
 * to simulate exactly what the installed hook scripts would do on the command line.
 * All other aspects - the real Git repository, real hook files on disk, real VSUM with
 * EMF model files, and both watchers running simultaneously - are genuine.
 */
@DisplayName("Vitruvius model versioning: complete developer workflow")
class VitruviusModelVersioningIntegrationTest {

    private static final Logger LOGGER = LogManager.getLogger(VitruviusModelVersioningIntegrationTest.class);

    private static final String COMMIT_SHA_MASTER_1 = "aaa1111111111111111111111111111111111111";
    private static final String COMMIT_SHA_FEATURE_1 = "bbb2222222222222222222222222222222222222";
    private static final String COMMIT_SHA_MASTER_2 = "ccc3333333333333333333333333333333333333";
    private static final String BRANCH_MASTER = "master";
    private static final String BRANCH_FEAT = "feature-model-update";

    @TempDir
    Path tempDir;

    private Git git;
    private InternalVirtualModel vsum;
    private VsumReloadWatcher reloadWatcher;
    private VsumValidationWatcher validationWatcher;
    private VsumMergeWatcher mergeWatcher;
    private VsumPostCommitWatcher postCommitWatcher;

    @BeforeAll
    static void registerResourceFactory() {
        // Required for EMF to serialize and deserialize .xmi model correctly.
        // Without registration, resource creation might return null and commitChanges might throw NullPointerException inside DefaultStateBasedResolutionStrategy.
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
    }

    @BeforeEach
    void setUp() throws Exception {
        // initialize "master" as original branch name of repo
        // jgit actually sets up automatically original branch as master, here is just for more clarity
        git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch(BRANCH_MASTER).call();
        vsum = createVirtualModel(tempDir);
        addSystem(vsum, tempDir);

    }

    private void addSystem (InternalVirtualModel model, Path projectPath) {
        // create a new root model element and register it in a view at a specific URI
        // modifyView(): propagate changes through view, do not manipulate resources directly
        // registerRoot(): createResource(uri) -> resource.getContents().add(root) -> derive change -> propagate change => create persistent resource inside vsum
        modifyView(getDefaultView(model), view -> view.registerRoot(ModelFactory.eINSTANCE.createSystem(), URI.createFileURI(projectPath.toString() + "/example.model")));
    }

    @AfterEach
    void tearDown() throws Exception {
        // stop both watchers even if test failed to avoid thread leaks.
        if (validationWatcher != null) validationWatcher.stop();
        if (reloadWatcher != null) reloadWatcher.stop();
        if (mergeWatcher != null) mergeWatcher.stop();
        if (postCommitWatcher != null) postCommitWatcher.stop();
        if (git != null) git.close();
    }

    /**
     * Walks through the complete Vitruvius model versioning workflow from start to finish
     * in a single sequential scenario. Each numbered step corresponds to one developer
     * action and is verified before the next step begins.
     */
    @Test
    void modelVersioningWorkflow() throws Exception {
        var reloadTrigger = new ReloadTriggerFile(tempDir);
        var triggerFile = new ValidationTriggerFile(tempDir);
        var resultFile = new ValidationResultFile(tempDir);

        // 1.STEP: INITIAL SETUP
        // install hooks
        new GitHookInstaller(tempDir).installAllHooks();

        // verify whether hook scripts reference the correct trigger files.
        String preCommit = Files.readString(tempDir.resolve(".git/hooks/pre-commit"));
        assertTrue(preCommit.contains("validate-trigger"), "pre-commit hook must reference the validation trigger file correctly");
        String postCheckout = Files.readString(tempDir.resolve(".git/hooks/post-checkout"));
        assertTrue(postCheckout.contains("reload-trigger"), "post-checkout must reference the reload trigger file correctly");
        String postCommit = Files.readString(tempDir.resolve(".git/hooks/post-commit"));
        assertTrue(postCommit.contains("post-commit-trigger"), "post-commit hook must reference the post-commit trigger file");
        String postMerge = Files.readString(tempDir.resolve(".git/hooks/post-merge"));
        assertTrue(postMerge.contains("merge-trigger"), "post-merge hook must reference the post-merge trigger file");

        // start both watchers
        reloadWatcher = new VsumReloadWatcher(vsum, tempDir);
        validationWatcher = new VsumValidationWatcher(vsum, tempDir);
        mergeWatcher = new VsumMergeWatcher(vsum, tempDir);
        postCommitWatcher = new VsumPostCommitWatcher(tempDir);

        reloadWatcher.start();
        validationWatcher.start();
        mergeWatcher.start();
        postCommitWatcher.start();

        // make an initial commit since jgit requires one commit before any branch operation is possible
        // this commit has no Vitruvius involvement
        makeInitialCommit();

        // 2.STEP: MAKE A COMMIT ON MASTER BRANCH

        // developer modifies the model in vsum
        // this is a real change that will be validated before the commit is allowed to proceed.
        modifyView(getDefaultView(vsum), view -> {
            var system = view.getRootObjects(System.class).iterator().next();
            var component = ModelFactory.eINSTANCE.createComponent();
            component.setName("MainComponent");
            system.getComponents().add(component);
        });

        // on the command line, "git commit" would trigger the pre-commit hook script, which writes .vitruvius/validate-trigger containing the commit sha and branch name
        //jgit does not execute hook scripts, so we write the trigger file manually here to simulate exactly what the installed hook script would do.
        String requestId1 = triggerFile.createTrigger(COMMIT_SHA_MASTER_1, BRANCH_MASTER);

        // in the background: validationWatcher detects the trigger file (within 500ms), call PreCommitHandler.validate() against the real vsum
        // and write the 2 result files: .vitruvius/results/<requestId>.txt & .vitruvius/results/<requestId>.json
        // wait for both files to appear before reading to avoid race condition
        waitForBothResultFiles(resultFile, requestId1);

        // on the command line, the hook script reads the JSON result to decide whether to exit 0 (allow the commit) or exit 1 (block the commit with an error message).
        ValidationResult result1 = resultFile.readResult(requestId1);
        assertNotNull(result1, "result must be written after the first validation");
        assertTrue(result1.isValid(), "the initial model must pass validation on master");
        resultFile.deleteResult(requestId1);

        // on the command line, the pre-commit hook exits 0 and Git creates the commit, assigning a real SHA.
        // the post-commit hook then fires and write .vitruvius/post-commit-trigger with the real SHA from git rev-parse HEAD.
        // we simulate this by making a real JGit commit and using its real SHA.
        var commit1 = git.commit()
                .setMessage("Add MainComponent")
                .setAuthor("Test", "test@example.com")
                .call();
        String realSha1 = commit1.getName();
        var postCommitTrigger = new PostCommitTriggerFile(tempDir);
        postCommitTrigger.createTrigger(realSha1, BRANCH_MASTER);


        // // in the background: VsumPostCommitWatcher detects the trigger (within 500ms), calls PostCommitHandler.generateChangelog() with the real SHA,
        // and writes it to .vitruvius/changelogs/<shortSha>.txt as a permanent record of the VSUM state at the time of this commit.
        Path changelog1 = changelogPath(realSha1);
        waitUntilFileExists(changelog1, 2000);

        assertTrue(Files.exists(changelog1), "a changelog must be written after the first passing validation on main");

        String changelogContent1 = Files.readString(changelog1);
        // the file follows the git log --pretty=fuller layout defined in SemanticChangelog.writeTo().
        assertTrue(changelogContent1.contains("SEMANTIC CHANGELOG"), "changelog must contain the SEMANTIC CHANGELOG header");
        assertTrue(changelogContent1.contains("Commit:     " + realSha1), "changelog must contain the full commit SHA");
        assertTrue(changelogContent1.contains("Branch:     " + BRANCH_MASTER), "changelog must contain the branch name");
        assertTrue(changelogContent1.contains("Author:"), "changelog must contain the author field");
        assertTrue(changelogContent1.contains("AuthorDate:"), "changelog must contain the author date field");

        // the file changes section lists model files added, modified, or deleted in this commit
        assertTrue(changelogContent1.contains("FILE CHANGES"), "changelog must contain the FILE CHANGES section");
        assertTrue(changelogContent1.contains("No file changes detected."), "changelog must indicate no file changes until JGit diff integration is added");


        //3.STEP: DEVELOPER SWITCHES TO A FEATURE BRANCH
        // this is a real JGit branch switch -> the repository HEAD moves to BRANCH_FEAT
        git.branchCreate().setName(BRANCH_FEAT).call();
        git.checkout().setName(BRANCH_FEAT).call();

        // on the command line, "git checkout" would fire the post-checkout hook script, which writes .vitruvius/reload-trigger containing the new branch name.
        // JGit does not execute hook scripts, so we write the trigger file manually here.
        reloadTrigger.createTrigger(BRANCH_FEAT);

        // in the background: VsumReloadWatcher detects the trigger file (within 500ms), calls PostCheckoutHandler.reload()
        // -> VirtualModelImpl.reload() -> ResourceRepositoryImpl -> reloads all .xmi model files from disk, then deletes the trigger file.
        waitUntil(() -> !reloadTrigger.exists(), 2000);
        assertFalse(reloadTrigger.exists(), "reload trigger must be consumed by VsumReloadWatcher after the branch switch");

        // 4.STEP: DEVELOPER COMMITS ON THE FEATURE BRANCH
        // after a VSUM reload, existing view references are stale and return no root objects.
        // a fresh view must always be obtained after a reload before modifying the model.
        CommittableView viewOnFeat = getDefaultView(vsum);
        assertNotNull(viewOnFeat, "a fresh view must be obtainable from the VSUM after the reload");

        // the developer adds a feature-specific component
        // this change exists only on the feature branch and will be isolated from the main branch model state.
        modifyView(viewOnFeat, view -> {
            var system = view.getRootObjects(System.class).iterator().next();
            var component = ModelFactory.eINSTANCE.createComponent();
            component.setName("FeatureComponent");
            system.getComponents().add(component);
        });

        // same pre-commit flow as step 2: trigger written manually -> VsumValidationWatcher
        // validates the VSUM (now containing FeatureComponent) -> result files written.
        String requestId2 = triggerFile.createTrigger(COMMIT_SHA_FEATURE_1, BRANCH_FEAT);
        waitForBothResultFiles(resultFile, requestId2);

        ValidationResult result2 = resultFile.readResult(requestId2);
        assertNotNull(result2, "result must be written after the feature branch validation");
        assertTrue(result2.isValid(), "the model with FeatureComponent must pass validation on the feature branch");
        resultFile.deleteResult(requestId2);

        // pre-commit passes -> Git creates the commit -> post-commit hook fires with real SHA.
        // we simulate: make a real JGit commit, then write the post-commit trigger.
        var commit2 = git.commit()
                .setMessage("Add FeatureComponent")
                .setAuthor("Test", "test@example.com")
                .call();
        String realSha2 = commit2.getName();

        postCommitTrigger.createTrigger(realSha2, BRANCH_FEAT);

        // VsumValidationWatcher automatically writes a second changelog for this commit, independent of the first
        // each commit SHA maps to exactly one changelog file.
        Path changelog2 = changelogPath(realSha2);
        waitUntilFileExists(changelog2, 2000);
        assertTrue(Files.exists(changelog2), "a changelog must be written automatically for the feature branch commit");
        String changelogContent2 = Files.readString(changelog2);
        assertTrue(changelogContent2.contains("SEMANTIC CHANGELOG"), "feature changelog must contain the SEMANTIC CHANGELOG header");
        assertTrue(changelogContent2.contains("Commit:     " + realSha2), "feature changelog must contain the correct commit SHA");
        assertTrue(changelogContent2.contains("Branch:     " + BRANCH_FEAT), "feature changelog must reference the feature branch name");
        assertTrue(changelogContent2.contains("FILE CHANGES"), "feature changelog must contain the FILE CHANGES section");

        // each commit produces a distinct changelog file named after its short SHA.
        assertNotEquals(changelog1, changelog2, "each commit must produce a distinct changelog file");


        // 5.STEP: Developer switches back to master
        // same reload flow as step 3: real JGit checkout -> post-checkout hook simulated -> VsumReloadWatcher reloads the VSUM back to the main branch model state.
        git.checkout().setName(BRANCH_MASTER).call();
        reloadTrigger.createTrigger(BRANCH_MASTER);
        waitUntil(() -> !reloadTrigger.exists(), 2000);
        assertFalse(reloadTrigger.exists(), "reload trigger must be consumed by VsumReloadWatcher after switching back to main");

        // fresh view required after the reload; the VSUM now reflects the main branch state, which does not include FeatureComponent
        CommittableView viewBackOnMain = getDefaultView(vsum);
        assertNotNull(viewBackOnMain, "a fresh view must be obtainable after the reload back to main");

        // 6.STEP: DEVELOPER ADDS ANOTHER COMPONENT ON MAIN AND COMMITS
        // a second model change on main, distinct from FeatureComponent which only exists on the feature branch, demonstrating branch-isolated model state.
        modifyView(viewBackOnMain, view -> {
            var system = view.getRootObjects(System.class).iterator().next();
            var component = ModelFactory.eINSTANCE.createComponent();
            component.setName("MainComponent2");
            system.getComponents().add(component);
        });

        // same pre-commit flow as steps 2 and 4: trigger written -> VsumValidationWatcher
        // validates -> result files written -> changelog written automatically.
        String requestId3 = triggerFile.createTrigger(COMMIT_SHA_MASTER_2, BRANCH_MASTER);
        waitForBothResultFiles(resultFile, requestId3);

        ValidationResult result3 = resultFile.readResult(requestId3);
        assertNotNull(result3, "result must be written after the second main validation");
        assertTrue(result3.isValid(), "the model with MainComponent2 must pass validation after returning to main");
        resultFile.deleteResult(requestId3);

        // pre-commit passes -> real JGit commit -> post-commit hook fires with real SHA
        var commit3 = git.commit()
                .setMessage("Add MainComponent2")
                .setAuthor("Test", "test@example.com")
                .call();
        String realSha3 = commit3.getName();
        postCommitTrigger.createTrigger(realSha3, BRANCH_MASTER);

        // a third changelog is written
        // the changelogs directory now contains one file per real commit, forming a complete and traceable VSUM version history.
        Path changelog3 = changelogPath(realSha3);
        waitUntilFileExists(changelog3, 2000);
        assertTrue(Files.exists(changelog3), "a changelog must be written automatically for the second main commit");
        String changelogContent3 = Files.readString(changelog3);
        assertTrue(changelogContent3.contains("Commit:     " + realSha3), "second main changelog must contain the correct commit SHA");
        assertTrue(changelogContent3.contains("Branch:     " + BRANCH_MASTER), "second main changelog must reference the main branch");

        // the .vitruvius/changelogs/ directory now holds three independent files, one per commit SHA, forming the complete VSUM version history for this session.
        assertNotEquals(changelog1, changelog3, "the two main changelogs must be distinct files for different commits");
        assertNotEquals(changelog2, changelog3, "the feature and second main changelogs must be distinct files");


        // 7.STEP: developer merges the feature branch into master

        //this is a real JGit merge: Git creates a merge commit with two parents, combining the model state from main and the feature branch.
        //MergeResult gives us the real merge commit SHA so we can use it in the trigger file instead of a fake placeholder
        MergeResult mergeOutcome  = git.merge()
                .include(git.getRepository().resolve(BRANCH_FEAT))
                .setMessage("merge feature-model-update into master")
                .call();
        // extract the real merge commit SHA from JGit, his is the actual SHA that Git assigned to the merge commit
        String realMergeCommitSha = mergeOutcome.getNewHead().getName();
        assertNotNull(realMergeCommitSha, "JGit must produce a real merge commit SHA after the merge completes");

        // on command line, git merge would fire the post-merge hook script, which writes .vitruvius/merge-trigger containing the merge commit sha, source branch and target branch
        // jgit does not execute hook scripts, so we need to write the trigger file manually here
        var mergeTriggerFile = new MergeTriggerFile(tempDir);
        var mergeResultFile = new MergeResultFile(tempDir);
        String mergeRequestId = mergeTriggerFile.createTrigger(realMergeCommitSha, BRANCH_FEAT, BRANCH_MASTER);

        // in the background: VsumMergeWatcher detects the trigger file within 500ms, calls PostMergeHandler.validate() against the real merged vsum state,
        // writes the results files and writes permanent merge metadata record to .vitruvius/merges/<sha>.metadata
        // unlike pre-commit, this is non-blocking as the merge is already complete regardless of the outcome
        waitForMergeResultFiles(mergeResultFile, mergeRequestId, 3000);

        // the post-merge hook reads the result and displays a warning report if needed.
        // the result is always written - passing or failing - because the merge is done.
        ValidationResult mergeResult = mergeResultFile.readResult(mergeRequestId);
        assertNotNull(mergeResult, "merge result must be written after the merge trigger is processed");
        assertTrue(mergeResult.isValid(), "the merged VSUM state combining main and feature components must be consistent");

        // the permanent merge metadata file must be written to .vitruvius/merges/
        // regardless of the validation outcome - it is the audit trail for the merge (MG-6).
        assertTrue(mergeResultFile.metadataExists(realMergeCommitSha), "merge metadata must be written after the post-merge trigger is processed");

        // read and verify the metadata content captures the full merge context.
        var metadata = mergeResultFile.readMetadata(realMergeCommitSha);
        assertNotNull(metadata, "merge metadata must be readable after being written");
        assertEquals(realMergeCommitSha, metadata.get("mergeCommitSha"), "metadata must record the correct merge commit SHA");
        assertEquals(BRANCH_FEAT, metadata.get("sourceBranch"), "metadata must record the feature branch as the source of the merge");
        assertEquals(BRANCH_MASTER, metadata.get("targetBranch"), "metadata must record main as the target that received the merge");
        assertEquals(true, metadata.get("valid"), "metadata must record that the merged state passed validation");

        // the merge result files are request-scoped and cleaned up by the hook after reading.
        // the metadata file is permanent and must not be deleted.
        mergeResultFile.deleteResult(mergeRequestId);
        assertTrue(mergeResultFile.metadataExists(realMergeCommitSha), "metadata file must survive result file cleanup - it is a permanent record");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = java.lang.System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && java.lang.System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private static void waitForMergeResultFiles(MergeResultFile resultFile, String requestId, long timeoutMs) throws InterruptedException {
        long deadline = java.lang.System.currentTimeMillis() + timeoutMs;
        while (java.lang.System.currentTimeMillis() < deadline) {
            if (Files.exists(resultFile.getTextResultPath(requestId)) && Files.exists(resultFile.getJsonResultPath(requestId))) {
                return;
            }
            Thread.sleep(50);
        }
    }
    /**
     * Creates an initial Git commit so JGit can create and switch branches.
     * JGit requires at least one commit before branch operations are possible.
     */
    private void makeInitialCommit() throws Exception {
        Files.writeString(tempDir.resolve(".vitruvius-init"), "vitruvius init");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Initial message").setAuthor("vitruv-user", "vitruv-user@gmail.com").call();
    }

    private static void waitForBothResultFiles(ValidationResultFile resultFile, String requestId) throws InterruptedException {
        long deadline = java.lang.System.currentTimeMillis() + 2000;
        while (java.lang.System.currentTimeMillis() < deadline) {
            if (Files.exists(resultFile.getTextResultPath(requestId)) && Files.exists(resultFile.getJsonResultPath(requestId))) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private Path changelogPath(String commitSha) {
        return tempDir.resolve(".vitruvius").resolve("changelogs").resolve(commitSha.substring(0, 7) + ".txt");
    }

    private static void waitUntilFileExists(Path path, long timeoutMs) throws InterruptedException {
        long deadline = java.lang.System.currentTimeMillis() + timeoutMs;
        while (!Files.exists(path) && java.lang.System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private InternalVirtualModel createVirtualModel(Path projectPath) throws IOException {
        InternalVirtualModel model = new VirtualModelBuilder()
                .withStorageFolder(projectPath)
                .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
                .withChangePropagationSpecifications(new Model2Model2ChangePropagationSpecification())
                .buildAndInitialize();
        model.setChangePropagationMode(ChangePropagationMode.TRANSITIVE_CYCLIC);
        return model;
    }

    private CommittableView getDefaultView(InternalVirtualModel model) {
        var selector = model.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
        selector.getSelectableElements().stream().filter(element -> element instanceof System).forEach(element -> selector.setSelected(element, true));
        return selector.createView().withChangeDerivingTrait();
    }

    private void modifyView(CommittableView view, Consumer<CommittableView> modification) {
        modification.accept(view);
        view.commitChanges();
    }
}