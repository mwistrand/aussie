package aussie.core.model.gateway;

import java.util.Objects;

import aussie.core.model.service.ServiceRegistration;

public sealed interface ProxyPlan {

    String serviceId();

    record Ready(GatewayRequest originalRequest, PreparedProxyRequest request, ServiceRegistration service)
            implements ProxyPlan {

        public Ready {
            Objects.requireNonNull(originalRequest, "originalRequest");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(service, "service");
        }

        @Override
        public String serviceId() {
            return service.serviceId();
        }
    }

    record Rejected(GatewayResult result, String serviceId) implements ProxyPlan {

        public Rejected {
            Objects.requireNonNull(result, "result");
        }
    }
}
