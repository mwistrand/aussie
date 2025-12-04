package aussie.core.port.out;

/**
 * Provider for obtaining the configured ForwardedHeaderBuilder.
 * This allows the core to be agnostic about which header format is used
 * (X-Forwarded-* vs RFC 7239 Forwarded) - that decision is made by the adapter.
 */
public interface ForwardedHeaderBuilderProvider {

    ForwardedHeaderBuilder getBuilder();
}
