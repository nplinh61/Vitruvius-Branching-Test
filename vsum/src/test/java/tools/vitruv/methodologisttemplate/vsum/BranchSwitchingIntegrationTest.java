package tools.vitruv.methodologisttemplate.vsum;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
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
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.branch.BranchManager;
import tools.vitruv.framework.vsum.branch.PostCheckoutHandler;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.System;

/**
 * Integration test that verifies BranchManager + PostCheckoutHandler correctly reload models
 * when switching between Git branches.
 *
 * <h2>Test Purpose</h2>
 * This test proves that the VSUM correctly reflects the state
 * of the currently checked-out Git branch. When switching branches, all in-memory model instances
 * must be discarded and reloaded from disk to match the branch's file content.
 *
 * <h2>Test Scenario</h2>
 * <ol>
 *   <li>Create a VSUM with one component on the main branch</li>
 *   <li>Commit this state to Git</li>
 *   <li>Create and switch to a feature branch</li>
 *   <li>Add a second component on the feature branch</li>
 *   <li>Commit the feature branch state</li>
 *   <li>Switch back to main</li>
 *   <li>Verify that only the first component is present (second component should be gone)</li>
 * </ol>
 */
public class BranchSwitchingIntegrationTest {

    /**
     * Registers the XMI resource factory so EMF can load .xmi model files.
     * This is required infrastructure for any test that works with EMF models.
     */
    @BeforeAll
    static void setup() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
    }

    @Test
    @DisplayName("switching branches reloads models with correct content")
    void switchBranchesReloadsModels(@TempDir Path tempDir) throws Exception {
        //1.step: initialize git repo with an initial main branch
        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            //2.step: create a vsum with initial model content on main branch
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir);
            //verify initial state on main branch, whether the added component exists inside vsum
            CommittableView view = getDefaultView(vsum);
            assertEquals(1, view.getRootObjects(System.class).size());
            var system = view.getRootObjects(System.class).iterator().next();
            assertEquals(1, system.getComponents().size());
            assertEquals("MainComponent", system.getComponents().get(0).getName());
            //commit initial state to git
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit on main").call();

            //4.step: set up branch management and create feature branch
            // BranchManager to handle git branch operations
            // PostCheckoutHandler to handle post checkout actions
            var branchManager = new BranchManager(tempDir);
            var handler = new PostCheckoutHandler(vsum);
            branchManager.setPostCheckoutHandler(handler);

            // create a new branch "feature-test" with main as parent
            branchManager.createBranch("feature-test", "main");

            // switch to the feature-test branch
            branchManager.switchBranch("feature-test");
            view = getDefaultView(vsum);

            //5.Step: add new component on feature branch
            addComponentToView(view, "FeatureComponent");

            //verify if the system now has 2 components
            view = getDefaultView(vsum);
            system = view.getRootObjects(System.class).iterator().next();
            assertEquals(2, system.getComponents().size());
            assertTrue(system.getComponents().stream().anyMatch(c -> c.getName().equals("FeatureComponent")));

            //commit changes made on feature branch
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added FeatureComponent").call();

            //7.step: switch back to main branch
            branchManager.switchBranch("main");

            view = getDefaultView(vsum);
            system = view.getRootObjects(System.class).iterator().next();
            //verify if system only has one component and no FeatureComponent should exist
            assertEquals(1, system.getComponents().size());
            assertEquals("MainComponent", system.getComponents().get(0).getName());
            assertFalse(system.getComponents().stream().anyMatch(c -> c.getName().equals("FeatureComponent")), "FeatureComponent should not exist on main branch");
            vsum.dispose();
        }
    }

    /**
     * Adds a component with the given name to the system in the provided view.
     * @param view The view providing access to the model
     * @param componentName Name for the new component
     */
    private void addComponentToView(CommittableView view, String componentName) {
        var system = view.getRootObjects(System.class).iterator().next();
        var component = ModelFactory.eINSTANCE.createComponent();
        component.setName(componentName);
        system.getComponents().add(component);
        view.commitChanges();
    }

    /**
     * Creates a new VirtualModel
     * @param projectPath Directory where the VSUM should store its files
     * @return Initialized VirtualModel ready for use
     * @throws IOException if VSUM initialization fails
     */
    private InternalVirtualModel createVirtualModel(Path projectPath) throws IOException {
        return new VirtualModelBuilder()
                .withStorageFolder(projectPath)
                .withUserInteractorForResultProvider(new TestUserInteraction.ResultProvider(new TestUserInteraction()))
                .withChangePropagationSpecifications(new Model2Model2ChangePropagationSpecification())
                .buildAndInitialize();
    }

    /**
     * Helper method that creates a System with one Component and registers it in the VSUM.
     * Steps performed:
     * 1. Create a System object (root of our model hierarchy)
     * 2. Create a Component object with the given name
     * 3. Add the Component to the System
     * 4. Register the System as a root object in the VSUM at the specified URI
     * 5. Commit the changes (triggers consistency preservation, saves to disk)
     *
     * @param vsum        The virtual model to add the system to
     * @param projectPath Base path for generating the model file URI
     */
    private void addSystemWithComponent(VirtualModel vsum, Path projectPath) {
        var view = getDefaultView(vsum).withChangeDerivingTrait();
        var system = ModelFactory.eINSTANCE.createSystem();
        var component = ModelFactory.eINSTANCE.createComponent();
        component.setName("MainComponent");
        system.getComponents().add(component);
        view.registerRoot(system, URI.createFileURI(projectPath.toString() + "/example.model"));
        view.commitChanges();
    }

    /**
     * Creates a CommittableView that shows System objects from the VSUM.
     * @param vsum The virtual model to create a view of
     * @return A committable view showing System objects
     */
    private CommittableView getDefaultView(VirtualModel vsum) {
        var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
        selector.getSelectableElements().stream().filter(element -> element instanceof System).forEach(it -> selector.setSelected(it, true));
        return selector.createView().withChangeDerivingTrait();
    }
}