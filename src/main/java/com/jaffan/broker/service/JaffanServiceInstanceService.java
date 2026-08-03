package com.jaffan.broker.service;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.provision.DeprovisionMode;
import com.jaffan.broker.provision.Provisioner;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceExistsException;
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
 * Synchronous {@link ServiceInstanceService}: provision and deprovision tenant databases, and refuse
 * plan updates.
 *
 * <p>All work is blocking JDBC wrapped in {@code Mono.fromCallable}; on the servlet stack the OSB
 * controllers subscribe on the request thread, so this stays a straightforward synchronous broker —
 * it never returns 202 or implements last-operation polling.
 *
 * <p>Idempotency is derived entirely from the backing servers, keeping the broker stateless:
 * <ul>
 *   <li>tenant DB already on the requested plan's backend → 200 {@code instanceExisted=true};</li>
 *   <li>tenant DB on a <em>sibling</em> backend (same GUID, different plan) → 409;</li>
 *   <li>neither → create, 201.</li>
 * </ul>
 */
@Service
public class JaffanServiceInstanceService implements ServiceInstanceService {

    private static final Logger log = LoggerFactory.getLogger(JaffanServiceInstanceService.class);

    private final BackendRouter router;
    private final DeprovisionMode deprovisionMode;

    public JaffanServiceInstanceService(BackendRouter router, DeprovisionMode deprovisionMode) {
        this.router = router;
        this.deprovisionMode = deprovisionMode;
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
                // Same GUID already provisioned on this plan's backend -> idempotent 200.
                com.jaffan.broker.log.OperationLog.outcome(log, "provision", "exists-idempotent",
                        instanceId, null, planId, backend.key(), start);
                return CreateServiceInstanceResponse.builder().instanceExisted(true).build();
            }
            for (Backend sibling : router.siblingBackendsFor(planId)) {
                if (provisioner.databaseExists(sibling, instanceId)) {
                    // Same GUID exists under a different plan/backend -> conflict.
                    com.jaffan.broker.log.OperationLog.outcome(log, "provision", "conflict-different-plan",
                            instanceId, null, planId, sibling.key(), start);
                    throw new ServiceInstanceExistsException(instanceId, request.getServiceDefinitionId());
                }
            }

            provisioner.provision(backend, instanceId, extensionsFrom(request.getParameters()));
            com.jaffan.broker.log.OperationLog.success(log, "provision", instanceId, null, planId,
                    backend.key(), start);
            return CreateServiceInstanceResponse.builder().instanceExisted(false).build();
        } catch (ServiceInstanceExistsException e) {
            throw e; // already logged as an expected outcome
        } catch (RuntimeException e) {
            com.jaffan.broker.log.OperationLog.failure(log, "provision", instanceId, null, planId,
                    backend.key(), start, e);
            throw e;
        }
    }

    @Override
    public Mono<DeleteServiceInstanceResponse> deleteServiceInstance(DeleteServiceInstanceRequest request) {
        return Mono.fromCallable(() -> deprovision(request));
    }

    private DeleteServiceInstanceResponse deprovision(DeleteServiceInstanceRequest request) {
        long start = System.nanoTime();
        String instanceId = request.getServiceInstanceId();
        String planId = request.getPlanId();
        Backend backend = router.backendFor(planId);
        Provisioner provisioner = router.provisionerFor(backend);
        try {
            if (!provisioner.databaseExists(backend, instanceId)) {
                // Unknown (or already-deleted) instance -> 410 GONE, idempotently.
                com.jaffan.broker.log.OperationLog.outcome(log, "deprovision", "gone-idempotent",
                        instanceId, null, planId, backend.key(), start);
                throw new ServiceInstanceDoesNotExistException(instanceId);
            }
            provisioner.deprovision(backend, instanceId, deprovisionMode);
            com.jaffan.broker.log.OperationLog.outcome(log, "deprovision",
                    "success-" + deprovisionMode.name().toLowerCase(), instanceId, null, planId,
                    backend.key(), start);
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
        // Plans are not updateable. A dev<->prod change would mean migrating a tenant between two
        // physically separate servers, which this broker does not do. Return 422 with a clear reason.
        return Mono.error(new ServiceInstanceUpdateNotSupportedException(
                "Changing the plan of an existing service instance is not supported. The dev and prod "
                        + "plans live on separate database servers, so switching plans would require a "
                        + "cross-cluster data migration. Create a new service instance on the desired "
                        + "plan and migrate your data instead."));
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
