package com.jaffan.broker.service;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.provision.Provisioner;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceUpdateNotSupportedException;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.instance.UpdateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.UpdateServiceInstanceResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Synchronous {@link ServiceInstanceService}: provision tenant databases on the postgres-ha cluster,
 * retire them on deprovision, and refuse plan updates.
 *
 * <p>All work is blocking JDBC wrapped in {@code Mono.fromCallable}; on the servlet stack the OSB
 * controllers subscribe on the request thread, so this stays a straightforward synchronous broker —
 * it never returns 202 or implements last-operation polling.
 *
 * <p><b>Deprovision never deletes.</b> {@code cf delete-service} retires the tenant database: it is
 * renamed to {@code retired_<original>_<epochMillis>} with its data intact and connections to it are
 * blocked. Recovering or purging a retired database is a manual operator action, on purpose.
 *
 * <p>Idempotency is derived entirely from the backing cluster, keeping the broker stateless: a
 * tenant DB that already exists → 200 {@code instanceExisted=true}; otherwise create, 201. An
 * unknown (or already-retired) instance on delete → 410.
 */
@Service
public class JaffanServiceInstanceService implements ServiceInstanceService {

    private static final Logger log = LoggerFactory.getLogger(JaffanServiceInstanceService.class);

    private final BackendRouter router;

    public JaffanServiceInstanceService(BackendRouter router) {
        this.router = router;
    }

    @Override
    public Mono<CreateServiceInstanceResponse> createServiceInstance(CreateServiceInstanceRequest request) {
        return Mono.fromCallable(() -> provision(request));
    }

    private CreateServiceInstanceResponse provision(CreateServiceInstanceRequest request) {
        long start = System.nanoTime();
        String instanceId = request.getServiceInstanceId();
        String planId = request.getPlanId();
        Backend backend = router.backendFor(planId);
        Provisioner provisioner = router.provisionerFor(backend);
        try {
            if (provisioner.databaseExists(backend, instanceId)) {
                // Same GUID already provisioned -> idempotent 200.
                com.jaffan.broker.log.OperationLog.outcome(log, "provision", "exists-idempotent",
                        instanceId, null, planId, backend.key(), start);
                return CreateServiceInstanceResponse.builder().instanceExisted(true).build();
            }

            provisioner.provision(backend, instanceId, extensionsFrom(request.getParameters()));
            com.jaffan.broker.log.OperationLog.success(log, "provision", instanceId, null, planId,
                    backend.key(), start);
            return CreateServiceInstanceResponse.builder().instanceExisted(false).build();
        } catch (RuntimeException e) {
            com.jaffan.broker.log.OperationLog.failure(log, "provision", instanceId, null, planId,
                    backend.key(), start, e);
            throw e;
        }
    }

    @Override
    public Mono<DeleteServiceInstanceResponse> deleteServiceInstance(DeleteServiceInstanceRequest request) {
        return Mono.fromCallable(() -> retire(request));
    }

    private DeleteServiceInstanceResponse retire(DeleteServiceInstanceRequest request) {
        long start = System.nanoTime();
        String instanceId = request.getServiceInstanceId();
        String planId = request.getPlanId();
        Backend backend = router.backendFor(planId);
        Provisioner provisioner = router.provisionerFor(backend);
        try {
            if (!provisioner.databaseExists(backend, instanceId)) {
                // Unknown (or already-retired) instance -> 410 GONE, idempotently.
                com.jaffan.broker.log.OperationLog.outcome(log, "deprovision", "gone-idempotent",
                        instanceId, null, planId, backend.key(), start);
                throw new ServiceInstanceDoesNotExistException(instanceId);
            }
            provisioner.retire(backend, instanceId);
            com.jaffan.broker.log.OperationLog.outcome(log, "deprovision", "success-retired",
                    instanceId, null, planId, backend.key(), start);
            return DeleteServiceInstanceResponse.builder().build();
        } catch (ServiceInstanceDoesNotExistException e) {
            throw e; // already logged
        } catch (RuntimeException e) {
            com.jaffan.broker.log.OperationLog.failure(log, "deprovision", instanceId, null, planId,
                    backend.key(), start, e);
            throw e;
        }
    }

    @Override
    public Mono<UpdateServiceInstanceResponse> updateServiceInstance(UpdateServiceInstanceRequest request) {
        // There is exactly one plan, so a plan change is meaningless. Return 422 with a clear reason.
        return Mono.error(new ServiceInstanceUpdateNotSupportedException(
                "Changing the plan of an existing service instance is not supported: this broker "
                        + "offers a single 'shared' plan on the postgres-ha cluster."));
    }

    @SuppressWarnings("unchecked")
    private List<String> extensionsFrom(Map<String, Object> parameters) {
        if (parameters == null) {
            return List.of();
        }
        Object value = parameters.get("extensions");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
