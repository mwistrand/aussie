package aussie.adapter.out.storage.memory;

import org.junit.jupiter.api.AfterEach;

import aussie.spi.TokenRevocationRepository;
import aussie.spi.TokenRevocationRepositoryContractTest;

/**
 * Contract test implementation for InMemoryTokenRevocationRepository.
 *
 * <p>Verifies that the in-memory implementation conforms to the
 * TokenRevocationRepository contract.
 */
class InMemoryTokenRevocationRepositoryContractTest extends TokenRevocationRepositoryContractTest {

    private InMemoryTokenRevocationRepository repository;

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.shutdown();
        }
    }

    @Override
    protected TokenRevocationRepository createRepository() {
        // Create a fresh repository for each test
        if (repository != null) {
            repository.shutdown();
        }
        repository = new InMemoryTokenRevocationRepository();
        return repository;
    }
}
