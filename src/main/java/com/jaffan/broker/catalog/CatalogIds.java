package com.jaffan.broker.catalog;

/**
 * Fixed identifiers for the catalog's service and plan.
 *
 * <p><b>WARNING — THESE UUIDs MUST NEVER CHANGE ONCE THE BROKER HAS BEEN REGISTERED.</b> Cloud
 * Controller persists them the moment {@code cf create-service-broker} runs, and it keys every
 * provisioned service instance and binding to the service/plan id it was created under. Changing an
 * id here would orphan every existing instance in every foundation the broker is registered with and
 * make deprovision/unbind impossible for them. They are random, generated once, and hardcoded on
 * purpose. If you ever need a genuinely new plan, add a new constant — never edit an existing one.
 */
public final class CatalogIds {

    private CatalogIds() {
    }

    public static final String POSTGRES_SERVICE_ID = "be70a127-bf7e-4669-8576-ed7c1abbdff5";
    public static final String POSTGRES_SHARED_PLAN_ID = "eb547b2c-a832-498d-910c-d1491052b356";
}
