package com.jaffan.broker.catalog;

/**
 * Fixed identifiers for the catalog's two services and four plans.
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
    public static final String POSTGRES_DEV_PLAN_ID = "eb1a4535-050b-4547-9eb0-4eda0407844f";
    public static final String POSTGRES_PROD_PLAN_ID = "9a5531d8-d85f-429c-a21c-4908c0f988c7";

    public static final String MARIADB_SERVICE_ID = "17116c65-c508-4ec1-8da9-b1bcdbc79532";
    public static final String MARIADB_DEV_PLAN_ID = "88e630e3-7213-48d9-a43f-a6654b820a92";
    public static final String MARIADB_PROD_PLAN_ID = "d54c4404-1f2f-4166-84be-4ce73a759a9a";
}
