package aussie.adapter.out.storage.redis;

/** Serializes lazy Quarkus Redis command bean initialization within one JVM. */
final class RedisCommandInitializationLock {

    static final Object INSTANCE = new Object();

    private RedisCommandInitializationLock() {}
}
