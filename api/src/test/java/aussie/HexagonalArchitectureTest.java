package aussie;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@DisplayName("Hexagonal Architecture Rules")
class HexagonalArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aussie");
    }

    @Nested
    @DisplayName("Core Layer Rules")
    class CoreLayerRules {

        @Test
        @DisplayName("Core should not depend on adapter")
        void coreShouldNotDependOnAdapter() {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.core..")
                    .should().dependOnClassesThat().resideInAPackage("aussie.adapter..");

            rule.check(importedClasses);
        }

        @Test
        @DisplayName("Core should not depend on system")
        void coreShouldNotDependOnSystem() {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.core..")
                    .should().dependOnClassesThat().resideInAPackage("aussie.system..");

            rule.check(importedClasses);
        }
    }

    @Nested
    @DisplayName("Adapter Layer Rules")
    class AdapterLayerRules {

        @Test
        @DisplayName("Adapter should not depend on system")
        void adapterShouldNotDependOnSystem() {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.adapter..")
                    .should().dependOnClassesThat().resideInAPackage("aussie.system..");

            rule.check(importedClasses);
        }
    }

    @Nested
    @DisplayName("Common Layer Rules")
    class CommonLayerRules {

        private boolean hasCommonPackage() {
            try {
                var commonClasses = importedClasses.getPackage("aussie.common");
                return commonClasses != null && !commonClasses.getClasses().isEmpty();
            } catch (IllegalArgumentException e) {
                // Package doesn't exist
                return false;
            }
        }

        @Test
        @DisplayName("Common should not depend on core")
        void commonShouldNotDependOnCore() {
            // Skip if no classes in common package (package is empty for now)
            if (!hasCommonPackage()) {
                return;
            }

            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.common..")
                    .should().dependOnClassesThat().resideInAPackage("aussie.core..");

            rule.check(importedClasses);
        }

        @Test
        @DisplayName("Common should not depend on adapter")
        void commonShouldNotDependOnAdapter() {
            // Skip if no classes in common package (package is empty for now)
            if (!hasCommonPackage()) {
                return;
            }

            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.common..")
                    .should().dependOnClassesThat().resideInAPackage("aussie.adapter..");

            rule.check(importedClasses);
        }

        @Test
        @DisplayName("Common should not depend on system")
        void commonShouldNotDependOnSystem() {
            // Skip if no classes in common package (package is empty for now)
            if (!hasCommonPackage()) {
                return;
            }

            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.common..")
                    .should().dependOnClassesThat().resideInAPackage("aussie.system..");

            rule.check(importedClasses);
        }
    }

    @Nested
    @DisplayName("Port Interface Rules")
    class PortInterfaceRules {

        @Test
        @DisplayName("Outbound ports should only contain interfaces")
        void outboundPortsShouldBeInterfaces() {
            ArchRule rule = ArchRuleDefinition.classes()
                    .that().resideInAPackage("aussie.core.port.out..")
                    .should().beInterfaces();

            rule.check(importedClasses);
        }
    }

    @Nested
    @DisplayName("Model Rules")
    class ModelRules {

        @Test
        @DisplayName("Models should not depend on services")
        void modelsShouldNotDependOnServices() {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.core.model..")
                    .should().dependOnClassesThat().resideInAPackage("aussie.core.service..");

            rule.check(importedClasses);
        }

        @Test
        @DisplayName("Models should not depend on ports")
        void modelsShouldNotDependOnPorts() {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.core.model..")
                    .should().dependOnClassesThat().resideInAPackage("aussie.core.port..");

            rule.check(importedClasses);
        }
    }
}
