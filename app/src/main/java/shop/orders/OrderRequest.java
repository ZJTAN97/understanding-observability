package shop.orders;

public record OrderRequest(String sku, Integer qty, String channel) {}
