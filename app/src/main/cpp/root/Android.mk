LOCAL_PATH := $(call my-dir)
MY_ROOT_PATH := $(LOCAL_PATH)

include $(MY_ROOT_PATH)/../hev-socks5-tunnel-src/Android.mk

include $(CLEAR_VARS)
LOCAL_PATH := $(MY_ROOT_PATH)/../hev-socks5-tunnel-src
SRCDIR := $(LOCAL_PATH)/src
include $(LOCAL_PATH)/build.mk
LOCAL_MODULE := hevroot
SRCFILES_FILTERED := $(filter-out $(SRCDIR)/hev-jni.c,$(SRCFILES))
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES_FILTERED))
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/src/misc \
	$(LOCAL_PATH)/src/core/include \
	$(LOCAL_PATH)/third-part/yaml/include \
	$(LOCAL_PATH)/third-part/lwip/src/include \
	$(LOCAL_PATH)/third-part/lwip/src/ports/include \
	$(LOCAL_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
LOCAL_CFLAGS += $(VERSION_CFLAGS)
LOCAL_CFLAGS += -ffunction-sections -fdata-sections
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
LOCAL_LDFLAGS += -Wl,--gc-sections -s
include $(BUILD_EXECUTABLE)
