idf_component_register(
    SRCS "focusmate_main.c" "face_observation.c" "camera_smoke.c" "face_detector.cpp"
         "shadow_posture.cpp" "frame_broker.cpp" "dashboard.cpp"
    INCLUDE_DIRS "."
    EMBED_TXTFILES "web/index.html"
    REQUIRES bt nvs_flash esp_timer esp32-camera human_face_detect esp_http_server
             esp_wifi esp_event esp_netif mdns json heap
)
