package com.jaffan.broker.service;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.provision.Provisioner;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceBindingExistsException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceBindingDoesNotExistException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceAppBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.DeleteServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.DeleteServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceBindingService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Synchronous {@link ServiceInstanceBindingService}: mint and revoke per-binding database credentials.
 *
 * <p>Because the broker stores nothing, bind idempotency is handled honestly: a login role/user is a
 * pure function of the binding GUID, but its password is not recoverable once generated. So a repeat
 * bind for an already-existing binding returns 409 ({@link ServiceInstanceBindingExistsException})
 * rather than fabricating fresh credentials that would not match the live role's password.
 */
@Service
public class JaffanServiceInstanceBindingService implements ServiceInstanceBindingService {

    private static final Logger log =
            LoggerFactory.getLogger(JaffanServiceInstanceBindingService.class);

    private final BackendRouter router;

    public JaffanServiceInstanceBindingService(BackendRouter router) {
        this.router = router;
    }

    @Override
    public Mono<CreateServiceInstanceBindingResponse> createServiceInstanceBinding(
            CreateServiceInstanceBindingRequest request) {
        return Mono.fromCallable(() -> bind(request));
    }

    private CreateServiceInstanceBindingResponse bind(CreateServiceInstanceBindingRequest request) {
        long start = System.nanoTime();
        String instanceId = request.getServiceInstanceId();
        String bindingId = request.getBindingId();
        String planId = request.getPlanId();
        Backend backend = router.backendFor(planId);
        Provisioner provisioner = router.provisionerFor(backend);
        try {
            if (!provisioner.databaseExists(backend, instanceId)) {
                // Binding a non-existent instance is a client error.
                throw new ServiceInstanceDoesNotExistException(instanceId);
            }
            if (provisioner.bindingExists(backend, bindingId)) {
                // Login role/user already present; we cannot reproduce its password statelessly.
                com.jaffan.broker.log.OperationLog.outcome(log, "bind", "conflict-exists", instanceId,
                        bindingId, planId, backend.key(), start);
                throw new ServiceInstanceBindingExistsException(instanceId, bindingId);
            }

            Map<String, Object> credentials = provisioner.bind(backend, instanceId, bindingId);
            com.jaffan.broker.log.OperationLog.success(log, "bind", instanceId, bindingId, planId,
                    backend.key(), start);
            return CreateServiceInstanceAppBindingResponse.builder()
                    .credentials(credentials)
                    .bindingExisted(false)
                    .build();
        } catch (ServiceInstanceBindingExistsException | ServiceInstanceDoesNotExistException e) {
            throw e;
        } catch (RuntimeException e) {
            com.jaffan.broker.log.OperationLog.failure(log, "bind", instanceId, bindingId, planId,
                    backend.key(), start, e);
            throw e;
        }
    }

    @Override
    public Mono<DeleteServiceInstanceBindingResponse> deleteServiceInstanceBinding(
            DeleteServiceInstanceBindingRequest request) {
        return Mono.fromCallable(() -> unbind(request));
    }

    private DeleteServiceInstanceBindingResponse unbind(DeleteServiceInstanceBindingRequest request) {
        long start = System.nanoTime();
        String instanceId = request.getServiceInstanceId();
        String bindingId = request.getBindingId();
        String planId = request.getPlanId();
        Backend backend = router.backendFor(planId);
        Provisioner provisioner = router.provisionerFor(backend);
        try {
            if (!provisioner.bindingExists(backend, bindingId)) {
                // Unknown binding -> 410 GONE, idempotently.
                com.jaffan.broker.log.OperationLog.outcome(log, "unbind", "gone-idempotent", instanceId,
                        bindingId, planId, backend.key(), start);
                throw new ServiceInstanceBindingDoesNotExistException(bindingId);
            }
            provisioner.unbind(backend, instanceId, bindingId);
            com.jaffan.broker.log.OperationLog.success(log, "unbind", instanceId, bindingId, planId,
                    backend.key(), start);
            return DeleteServiceInstanceBindingResponse.builder().build();
        } catch (ServiceInstanceBindingDoesNotExistException e) {
            throw e;
        } catch (RuntimeException e) {
            com.jaffan.broker.log.OperationLog.failure(log, "unbind", instanceId, bindingId, planId,
                    backend.key(), start, e);
            throw e;
        }
    }
}
