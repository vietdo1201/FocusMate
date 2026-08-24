idf_component_register(
    SRCS "focusmate_dns.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_netif lwip
)
