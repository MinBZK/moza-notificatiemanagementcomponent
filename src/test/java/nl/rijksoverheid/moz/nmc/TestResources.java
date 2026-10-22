package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.common.QuarkusTestResource;

/**
 * Registreert de globale testresources. Quarkus vindt {@code @QuarkusTestResource} op elke klasse
 * in de testmodule; de resource geldt daarmee voor elke {@code @QuarkusTest}.
 */
@QuarkusTestResource(EmbeddedPostgresTestResource.class)
public final class TestResources {

    private TestResources() {
    }
}
