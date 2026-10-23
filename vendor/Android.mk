LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_C_INCLUDES += $(LOCAL_PATH)/cdict/libcdict
LOCAL_CFLAGS +=
# -Wall -Wextra -Werror
LOCAL_SRC_FILES := cdict/libcdict/libcdict.c cdict/java/jni/juloo_cdict_Cdict.c
LOCAL_MODULE := libcdict_java
LOCAL_SDK_VERSION := 23

# Android requires 16K pages
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
# Disable build id to ensure reproducibility, needed for F-Droid
LOCAL_LDFLAGS += -Wl,--build-id=none

include $(BUILD_SHARED_LIBRARY)

# Rime support. The prebuilt libraries are downloaded by
# scripts/fetch_rime.sh.

RIME_PREBUILT := $(LOCAL_PATH)/rime-prebuilt

define rime_prebuilt_static
include $(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_SRC_FILES := rime-prebuilt/$(2)/$(TARGET_ARCH_ABI)/lib/$(3)
include $(PREBUILT_STATIC_LIBRARY)
endef

$(eval $(call rime_prebuilt_static,rime_static,librime,librime.a))
$(eval $(call rime_prebuilt_static,rime_glog,glog,libglog.a))
$(eval $(call rime_prebuilt_static,rime_leveldb,leveldb,libleveldb.a))
$(eval $(call rime_prebuilt_static,rime_lua,lua,liblua_static.a))
$(eval $(call rime_prebuilt_static,rime_marisa,marisa,libmarisa.a))
$(eval $(call rime_prebuilt_static,rime_opencc,opencc,libopencc.a))
$(eval $(call rime_prebuilt_static,rime_yaml_cpp,yaml-cpp,libyaml-cpp.a))

include $(CLEAR_VARS)

LOCAL_MODULE := librime_jni
LOCAL_SRC_FILES := rime_jni/rime_jni.cc
LOCAL_C_INCLUDES += $(RIME_PREBUILT)/librime/$(TARGET_ARCH_ABI)/include
LOCAL_CPPFLAGS += -std=c++17
# Modules are registered by static constructors, the whole archive must be
# linked.
LOCAL_WHOLE_STATIC_LIBRARIES := rime_static
LOCAL_STATIC_LIBRARIES := rime_leveldb rime_lua rime_marisa rime_opencc \
  rime_yaml_cpp rime_glog
LOCAL_LDLIBS := -llog
LOCAL_SDK_VERSION := 23

LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,--build-id=none
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL
LOCAL_LDFLAGS += -Wl,--gc-sections

include $(BUILD_SHARED_LIBRARY)
