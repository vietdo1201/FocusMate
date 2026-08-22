idf_component_register(
    SRCS "focusmate_main.c" "face_observation.c" "camera_smoke.c"
    INCLUDE_DIRS "."
    REQUIRES bt nvs_flash esp_timer esp32-camera
)
