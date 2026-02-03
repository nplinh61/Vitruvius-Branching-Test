package tools.vitruv.methodologisttemplate.vsum;

import mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.branch.GitHookInstaller;
import tools.vitruv.framework.vsum.branch.ReloadTriggerFile;
import tools.vitruv.framework.vsum.branch.VsumReloadWatcher;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test that proves Git hook integration works.
 * This test uses JGit for setup but manually creates trigger files
 * to simulate hook execution, because JGit doesn't run Git hooks.
 * In a real deployment, the Git hook script would be executed by native Git
 * when developers use 'git checkout' from the command line.
 */
class GitCheckoutIntegrationTest {

    @BeforeAll
    static void setup() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
    }

    @Test
    @DisplayName("hook and watcher enable automatic reload on branch switch")
    void hookAndWatcherEnableAutomaticReload(@TempDir Path tempDir) throws Exception {
        // 1.step: initialize git repository with two branches
        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            
            // 2.step: create VSUM with a single MainComponent on main branch
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "MainComponent");
            // verify initial state
            CommittableView view = getDefaultView(vsum);
            assertEquals(1, view.getRootObjects(tools.vitruv.methodologisttemplate.model.model.System.class).size());
            var system = view.getRootObjects(tools.vitruv.methodologisttemplate.model.model.System.class).iterator().next();
            assertEquals(1, system.getComponents().size());
            assertEquals("MainComponent", system.getComponents().get(0).getName());
            // commit on main
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit on main").call();
            
            
            // 3.step: create feature branch from main with different content
            git.branchCreate().setName("feature-test").call();
            git.checkout().setName("feature-test").call();
            // reload after JGit checkout
            vsum.reload();
            // add different component on feature branch
            view = getDefaultView(vsum);
            addComponentToView(view, "FeatureComponent");
            // commit on feature branch
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added FeatureComponent").call();
            
            
            // 4.step: install Git Hook
            var hookInstaller = new GitHookInstaller(tempDir);
            hookInstaller.installPostCheckoutHook();
            assertTrue(hookInstaller.isPostCheckoutHookInstalled(), "Hook should be installed");
            
            
            // 5.step: Start Watcher
            var watcher = new VsumReloadWatcher(vsum, tempDir);
            watcher.start();
            assertTrue(watcher.isRunning(), "Watcher should be running");
            // Give watcher time to start
            Thread.sleep(200);
            
            
            // 6.step: switch to main branch
            // simulate git hook execution
            // manually create the trigger file to simulate post-checkout hook
            git.checkout().setName("main").call();
            var triggerFile = new ReloadTriggerFile(tempDir);
            triggerFile.createTrigger();
            Thread.sleep(1500);
            
            // 7.step: verify if vsum is reloaded
            view = getDefaultView(vsum);
            system = view.getRootObjects(tools.vitruv.methodologisttemplate.model.model.System.class).iterator().next();
            // should have only MainComponent (FeatureComponent should be gone)
            assertEquals(1, system.getComponents().size(), "Should have 1 component after reload to main branch");
            assertEquals("MainComponent", system.getComponents().get(0).getName(), "Should have MainComponent on main branch");
            assertFalse(system.getComponents().stream().anyMatch(c -> c.getName().equals("FeatureComponent")), "FeatureComponent should not exist on main branch after reload");
            
            // 8.step: switch back to feature branch
            git.checkout().setName("feature-test").call();
            // simulate hook execution again
            triggerFile.createTrigger();
            Thread.sleep(1500);  
            view = getDefaultView(vsum);
            system = view.getRootObjects(tools.vitruv.methodologisttemplate.model.model.System.class).iterator().next();

            // should have both components on feature branch
            assertEquals(2, system.getComponents().size(), "Should have 2 components on feature branch after reload");
            assertTrue(system.getComponents().stream().anyMatch(c -> c.getName().equals("MainComponent")), "Should have MainComponent");
            assertTrue(system.getComponents().stream().anyMatch(c -> c.getName().equals("FeatureComponent")), "Should have FeatureComponent");
            watcher.stop();
            vsum.dispose();
        }
    }

    /**
     * Creates a VirtualModel (VSUM) in the given directory.
     */
    private InternalVirtualModel createVirtualModel(Path projectPath) throws IOException {
        return new VirtualModelBuilder()
                .withStorageFolder(projectPath)
                .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
                .withChangePropagationSpecifications(new Model2Model2ChangePropagationSpecification())
                .buildAndInitialize();
    }

    /**
     * Creates a System with one Component and registers it in the VSUM.
     */
    private void addSystemWithComponent(InternalVirtualModel vsum, Path projectPath, String componentName) {
        var view = getDefaultView(vsum).withChangeDerivingTrait();
        var system = ModelFactory.eINSTANCE.createSystem();
        var component = ModelFactory.eINSTANCE.createComponent();
        component.setName(componentName);
        system.getComponents().add(component);
        view.registerRoot(system, URI.createFileURI(projectPath.toString() + "/example.model"));
        view.commitChanges();
    }

    /**
     * Adds a component to the system in the provided view.
     */
    private void addComponentToView(CommittableView view, String componentName) {
        var system = view.getRootObjects(tools.vitruv.methodologisttemplate.model.model.System.class).iterator().next();
        var component = ModelFactory.eINSTANCE.createComponent();
        component.setName(componentName);
        system.getComponents().add(component);
        view.commitChanges();
    }

    /**
     * Creates a view that shows System objects from the VSUM.
     */
    private CommittableView getDefaultView(InternalVirtualModel vsum) {
        var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
        selector.getSelectableElements().stream()
                .filter(element -> element instanceof tools.vitruv.methodologisttemplate.model.model.System)
                .forEach(it -> selector.setSelected(it, true));
        return selector.createView().withChangeDerivingTrait();
    }
}