// DuoFold Vulkan renderer: imports each captured AHardwareBuffer as a VkImage and
// renders the folded quad with the fold shader (SPIR-V), presented via a swapchain.
#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <mutex>
#include <thread>
#include <atomic>
#include <vector>
#include <cstring>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DuoFoldVk", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DuoFoldVk", __VA_ARGS__)

#include "vk_fold_vert.inc"
#include "vk_fold_frag.inc"

namespace {

// Must match the push_constant layout of vk_fold.frag.
struct Push {
    float matrix[16];
    float resolution[2];
    float angles[2];
    float sizePoints[2];
    float intensity;
    float blurScale;
    float stretch;
};

JavaVM* g_vm = nullptr;
jobject g_viewRef = nullptr;
jclass g_viewClass = nullptr;
jmethodID g_onFirstFrame = nullptr;

static void notifyFirstFrame() {
    if (!g_vm || !g_viewRef || !g_onFirstFrame) return;
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    env->CallVoidMethod(g_viewRef, g_onFirstFrame);
    g_vm->DetachCurrentThread();
}

struct VkRenderer {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    ANativeWindow* window = nullptr;

    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat swapFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D extent{};
    std::vector<VkImage> swapImages;
    std::vector<VkImageView> swapViews;
    std::vector<VkFramebuffer> framebuffers;
    VkRenderPass renderPass = VK_NULL_HANDLE;

    VkDescriptorSetLayout descLayout = VK_NULL_HANDLE;
    VkDescriptorPool descPool = VK_NULL_HANDLE;
    VkDescriptorSet descSet = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;

    VkCommandPool cmdPool = VK_NULL_HANDLE;
    VkCommandBuffer cmdBuf = VK_NULL_HANDLE;
    VkSemaphore acquireSem = VK_NULL_HANDLE;
    VkSemaphore renderSem = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;

    AHardwareBuffer* latest = nullptr;
    std::mutex bufMutex;
    VkImage importedImage = VK_NULL_HANDLE;
    VkDeviceMemory importedMemory = VK_NULL_HANDLE;
    VkImageView importedView = VK_NULL_HANDLE;
    bool hasImage = false;

    std::mutex paramMutex;
    Push push{};
    std::atomic<bool> running{false};
    std::atomic<bool> swapDirty{false};
    std::thread thread;

    PFN_vkCreateAndroidSurfaceKHR pfnCreateAndroidSurfaceKHR = nullptr;
    PFN_vkDestroySurfaceKHR pfnDestroySurfaceKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR pfnGetSurfaceSupport = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR pfnGetSurfaceCaps = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR pfnGetSurfaceFormats = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR pfnGetSurfaceModes = nullptr;
    PFN_vkCreateSwapchainKHR pfnCreateSwapchainKHR = nullptr;
    PFN_vkDestroySwapchainKHR pfnDestroySwapchainKHR = nullptr;
    PFN_vkGetSwapchainImagesKHR pfnGetSwapchainImagesKHR = nullptr;
    PFN_vkAcquireNextImageKHR pfnAcquireNextImageKHR = nullptr;
    PFN_vkQueuePresentKHR pfnQueuePresentKHR = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID pfnGetAhbProps = nullptr;

    bool loadInstanceFuncs() {
        pfnCreateAndroidSurfaceKHR = (PFN_vkCreateAndroidSurfaceKHR)vkGetInstanceProcAddr(instance, "vkCreateAndroidSurfaceKHR");
        pfnDestroySurfaceKHR = (PFN_vkDestroySurfaceKHR)vkGetInstanceProcAddr(instance, "vkDestroySurfaceKHR");
        pfnGetSurfaceSupport = (PFN_vkGetPhysicalDeviceSurfaceSupportKHR)vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfaceSupportKHR");
        pfnGetSurfaceCaps = (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
        pfnGetSurfaceFormats = (PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfaceFormatsKHR");
        pfnGetSurfaceModes = (PFN_vkGetPhysicalDeviceSurfacePresentModesKHR)vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfacePresentModesKHR");
        return pfnCreateAndroidSurfaceKHR && pfnDestroySurfaceKHR && pfnGetSurfaceSupport &&
               pfnGetSurfaceCaps && pfnGetSurfaceFormats && pfnGetSurfaceModes;
    }
    bool loadDeviceFuncs() {
        pfnCreateSwapchainKHR = (PFN_vkCreateSwapchainKHR)vkGetDeviceProcAddr(device, "vkCreateSwapchainKHR");
        pfnDestroySwapchainKHR = (PFN_vkDestroySwapchainKHR)vkGetDeviceProcAddr(device, "vkDestroySwapchainKHR");
        pfnGetSwapchainImagesKHR = (PFN_vkGetSwapchainImagesKHR)vkGetDeviceProcAddr(device, "vkGetSwapchainImagesKHR");
        pfnAcquireNextImageKHR = (PFN_vkAcquireNextImageKHR)vkGetDeviceProcAddr(device, "vkAcquireNextImageKHR");
        pfnQueuePresentKHR = (PFN_vkQueuePresentKHR)vkGetDeviceProcAddr(device, "vkQueuePresentKHR");
        pfnGetAhbProps = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(
                device, "vkGetAndroidHardwareBufferPropertiesANDROID");
        return pfnCreateSwapchainKHR && pfnDestroySwapchainKHR && pfnGetSwapchainImagesKHR &&
               pfnAcquireNextImageKHR && pfnQueuePresentKHR && pfnGetAhbProps;
    }

    bool init(JNIEnv* env, jobject jsurface, int w, int h) {
        window = ANativeWindow_fromSurface(env, jsurface);
        if (!window) { LOGE("ANativeWindow_fromSurface failed"); return false; }

        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "DuoFold";
        app.applicationVersion = 1;
        app.apiVersion = VK_API_VERSION_1_0;
        const char* instExts[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };
        VkInstanceCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ici.pApplicationInfo = &app;
        ici.enabledExtensionCount = 2;
        ici.ppEnabledExtensionNames = instExts;
        if (vkCreateInstance(&ici, nullptr, &instance) != VK_SUCCESS) { LOGE("vkCreateInstance failed"); return false; }
        if (!loadInstanceFuncs()) { LOGE("instance funcs missing"); return false; }

        uint32_t count = 0;
        vkEnumeratePhysicalDevices(instance, &count, nullptr);
        if (!count) { LOGE("no physical device"); return false; }
        std::vector<VkPhysicalDevice> devs(count);
        vkEnumeratePhysicalDevices(instance, &count, devs.data());
        phys = devs[0];

        VkAndroidSurfaceCreateInfoKHR sci{};
        sci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        sci.window = window;
        if (pfnCreateAndroidSurfaceKHR(instance, &sci, nullptr, &surface) != VK_SUCCESS) {
            LOGE("vkCreateAndroidSurfaceKHR failed"); return false;
        }

        const char* devExts[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                                  VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME };
        uint32_t availCount = 0;
        vkEnumerateDeviceExtensionProperties(phys, nullptr, &availCount, nullptr);
        std::vector<VkExtensionProperties> avail(availCount);
        vkEnumerateDeviceExtensionProperties(phys, nullptr, &availCount, avail.data());
        bool haveSwap = false, haveAhb = false;
        for (auto& e : avail) {
            if (!strcmp(e.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) haveSwap = true;
            if (!strcmp(e.extensionName, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) haveAhb = true;
        }
        if (!haveSwap || !haveAhb) { LOGE("missing device extensions swap=%d ahb=%d", haveSwap, haveAhb); return false; }

        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(phys, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(phys, &qCount, qProps.data());
        queueFamily = UINT32_MAX;
        for (uint32_t i = 0; i < qCount; i++) {
            if (!(qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
            VkBool32 supported = VK_FALSE;
            pfnGetSurfaceSupport(phys, i, surface, &supported);
            if (supported) { queueFamily = i; break; }
        }
        if (queueFamily == UINT32_MAX) { LOGE("no graphics+present queue"); return false; }

        float qPriority = 1.0f;
        VkDeviceQueueCreateInfo dq{};
        dq.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        dq.queueFamilyIndex = queueFamily;
        dq.queueCount = 1;
        dq.pQueuePriorities = &qPriority;
        VkDeviceCreateInfo dc{};
        dc.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dc.queueCreateInfoCount = 1;
        dc.pQueueCreateInfos = &dq;
        dc.enabledExtensionCount = 2;
        dc.ppEnabledExtensionNames = devExts;
        if (vkCreateDevice(phys, &dc, nullptr, &device) != VK_SUCCESS) { LOGE("vkCreateDevice failed"); return false; }
        vkGetDeviceQueue(device, queueFamily, 0, &queue);
        if (!loadDeviceFuncs()) { LOGE("device funcs missing"); return false; }

        if (!createSwapchain(w, h)) return false;
        if (!createPipeline()) return false;   // creates renderPass
        if (!createFramebuffers()) return false;
        if (!createCommands()) return false;

        std::memset(&push, 0, sizeof(push));
        push.matrix[0] = 1; push.matrix[5] = 1; push.matrix[10] = 1; push.matrix[15] = 1;
        running = true;
        thread = std::thread([this] { renderLoop(); });
        return true;
    }

    bool createSwapchain(int w, int h) {
        destroySwapchain();
        if (!pfnGetSurfaceCaps) return false;
        VkSurfaceCapabilitiesKHR caps;
        if (pfnGetSurfaceCaps(phys, surface, &caps) != VK_SUCCESS) return false;
        extent.width = w > 0 ? (uint32_t)w : caps.currentExtent.width;
        extent.height = h > 0 ? (uint32_t)h : caps.currentExtent.height;
        if (extent.width == 0 || extent.height == 0) {
            extent = caps.currentExtent;
            if (extent.width == 0 || extent.height == 0) { LOGE("bad extent"); return false; }
        }

        uint32_t fmtCount = 0;
        pfnGetSurfaceFormats(phys, surface, &fmtCount, nullptr);
        std::vector<VkSurfaceFormatKHR> fmts(fmtCount);
        pfnGetSurfaceFormats(phys, surface, &fmtCount, fmts.data());
        swapFormat = VK_FORMAT_R8G8B8A8_UNORM;
        VkColorSpaceKHR colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        for (auto& f : fmts) {
            if (f.format == VK_FORMAT_R8G8B8A8_UNORM) { swapFormat = f.format; colorSpace = f.colorSpace; break; }
        }

        uint32_t modeCount = 0;
        pfnGetSurfaceModes(phys, surface, &modeCount, nullptr);
        std::vector<VkPresentModeKHR> modes(modeCount);
        pfnGetSurfaceModes(phys, surface, &modeCount, modes.data());
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
        for (auto& m : modes) if (m == VK_PRESENT_MODE_FIFO_KHR) presentMode = m;

        VkSwapchainCreateInfoKHR sc{};
        sc.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        sc.surface = surface;
        sc.minImageCount = std::max(2u, caps.minImageCount);
        sc.imageFormat = swapFormat;
        sc.imageColorSpace = colorSpace;
        sc.imageExtent = extent;
        sc.imageArrayLayers = 1;
        sc.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        sc.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        sc.preTransform = caps.currentTransform;
        sc.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        sc.presentMode = presentMode;
        sc.clipped = VK_TRUE;
        if (pfnCreateSwapchainKHR(device, &sc, nullptr, &swapchain) != VK_SUCCESS) { LOGE("vkCreateSwapchainKHR failed"); return false; }
        uint32_t n = 0;
        pfnGetSwapchainImagesKHR(device, swapchain, &n, nullptr);
        swapImages.resize(n);
        pfnGetSwapchainImagesKHR(device, swapchain, &n, swapImages.data());
        return true;
    }

    bool createFramebuffers() {
        if (!device || !renderPass) { LOGE("createFramebuffers: renderPass missing"); return false; }
        swapViews.resize(swapImages.size());
        framebuffers.resize(swapImages.size());
        for (uint32_t i = 0; i < swapImages.size(); i++) {
            VkImageViewCreateInfo vi{};
            vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            vi.image = swapImages[i];
            vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
            vi.format = swapFormat;
            vi.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
            vkCreateImageView(device, &vi, nullptr, &swapViews[i]);
            VkFramebufferCreateInfo fb{};
            fb.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fb.renderPass = renderPass;
            fb.attachmentCount = 1;
            fb.pAttachments = &swapViews[i];
            fb.width = extent.width;
            fb.height = extent.height;
            fb.layers = 1;
            vkCreateFramebuffer(device, &fb, nullptr, &framebuffers[i]);
        }
        return true;
    }

    void destroySwapchain() {
        if (!device) return;
        vkDeviceWaitIdle(device);
        for (auto f : framebuffers) if (f) vkDestroyFramebuffer(device, f, nullptr);
        for (auto v : swapViews) if (v) vkDestroyImageView(device, v, nullptr);
        framebuffers.clear(); swapViews.clear(); swapImages.clear();
        if (swapchain) pfnDestroySwapchainKHR(device, swapchain, nullptr);
        swapchain = VK_NULL_HANDLE;
    }

    bool createPipeline() {
        VkShaderModule vert = VK_NULL_HANDLE, frag = VK_NULL_HANDLE;
        VkShaderModuleCreateInfo vsc{};
        vsc.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        vsc.codeSize = vk_fold_vert_spv_len;
        vsc.pCode = (const uint32_t*)vk_fold_vert_spv;
        if (vkCreateShaderModule(device, &vsc, nullptr, &vert) != VK_SUCCESS) { LOGE("vert module fail"); return false; }
        VkShaderModuleCreateInfo fsc{};
        fsc.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        fsc.codeSize = vk_fold_frag_spv_len;
        fsc.pCode = (const uint32_t*)vk_fold_frag_spv;
        if (vkCreateShaderModule(device, &fsc, nullptr, &frag) != VK_SUCCESS) { LOGE("frag module fail"); return false; }

        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vert;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = frag;
        stages[1].pName = "main";

        VkPipelineVertexInputStateCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        VkPipelineInputAssemblyStateCreateInfo ia{};
        ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        VkPipelineViewportStateCreateInfo vp{};
        vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        VkViewport viewport{ 0, 0, (float)extent.width, (float)extent.height, 0, 1 };
        VkRect2D scissor{ {0,0}, extent };
        vp.viewportCount = 1; vp.pViewports = &viewport;
        vp.scissorCount = 1; vp.pScissors = &scissor;
        VkPipelineRasterizationStateCreateInfo rs{};
        rs.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rs.polygonMode = VK_POLYGON_MODE_FILL;
        rs.cullMode = VK_CULL_MODE_NONE;
        rs.lineWidth = 1.f;
        VkPipelineMultisampleStateCreateInfo ms{};
        ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState ba{};
        ba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        ba.blendEnable = VK_FALSE;
        VkPipelineColorBlendStateCreateInfo cb{};
        cb.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        cb.attachmentCount = 1; cb.pAttachments = &ba;

        VkAttachmentDescription color{};
        color.format = swapFormat;
        color.samples = VK_SAMPLE_COUNT_1_BIT;
        color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference ref{ 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1; sub.pColorAttachments = &ref;
        VkRenderPassCreateInfo rp{};
        rp.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        rp.attachmentCount = 1; rp.pAttachments = &color;
        rp.subpassCount = 1; rp.pSubpasses = &sub;
        if (vkCreateRenderPass(device, &rp, nullptr, &renderPass) != VK_SUCCESS) { LOGE("renderpass fail"); return false; }

        VkDescriptorSetLayoutBinding dslb{};
        dslb.binding = 0;
        dslb.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        dslb.descriptorCount = 1;
        dslb.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        VkDescriptorSetLayoutCreateInfo dl{};
        dl.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        dl.bindingCount = 1; dl.pBindings = &dslb;
        vkCreateDescriptorSetLayout(device, &dl, nullptr, &descLayout);

        VkPushConstantRange pc{};
        pc.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        pc.offset = 0;
        pc.size = sizeof(Push);
        VkPipelineLayoutCreateInfo pl{};
        pl.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pl.setLayoutCount = 1; pl.pSetLayouts = &descLayout;
        pl.pushConstantRangeCount = 1; pl.pPushConstantRanges = &pc;
        vkCreatePipelineLayout(device, &pl, nullptr, &pipelineLayout);

        VkGraphicsPipelineCreateInfo gp{};
        gp.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        gp.stageCount = 2; gp.pStages = stages;
        gp.pVertexInputState = &vi;
        gp.pInputAssemblyState = &ia;
        gp.pViewportState = &vp;
        gp.pRasterizationState = &rs;
        gp.pMultisampleState = &ms;
        gp.pColorBlendState = &cb;
        gp.layout = pipelineLayout;
        gp.renderPass = renderPass;
        gp.subpass = 0;
        if (vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &gp, nullptr, &pipeline) != VK_SUCCESS) {
            LOGE("graphics pipeline fail"); return false;
        }

        VkSamplerCreateInfo sm{};
        sm.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        sm.magFilter = VK_FILTER_LINEAR;
        sm.minFilter = VK_FILTER_LINEAR;
        sm.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sm.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sm.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sm.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sm.maxLod = 0.25f;
        vkCreateSampler(device, &sm, nullptr, &sampler);

        VkDescriptorPoolSize dps{};
        dps.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        dps.descriptorCount = 1;
        VkDescriptorPoolCreateInfo dp{};
        dp.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        dp.maxSets = 1; dp.poolSizeCount = 1; dp.pPoolSizes = &dps;
        vkCreateDescriptorPool(device, &dp, nullptr, &descPool);
        VkDescriptorSetAllocateInfo dsa{};
        dsa.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        dsa.descriptorPool = descPool;
        dsa.descriptorSetCount = 1; dsa.pSetLayouts = &descLayout;
        vkAllocateDescriptorSets(device, &dsa, &descSet);

        vkDestroyShaderModule(device, vert, nullptr);
        vkDestroyShaderModule(device, frag, nullptr);
        return true;
    }

    bool createCommands() {
        VkCommandPoolCreateInfo cp{};
        cp.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        cp.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cp.queueFamilyIndex = queueFamily;
        vkCreateCommandPool(device, &cp, nullptr, &cmdPool);
        VkCommandBufferAllocateInfo ca{};
        ca.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ca.commandPool = cmdPool;
        ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ca.commandBufferCount = 1;
        vkAllocateCommandBuffers(device, &ca, &cmdBuf);
        VkSemaphoreCreateInfo se{};
        se.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        vkCreateSemaphore(device, &se, nullptr, &acquireSem);
        vkCreateSemaphore(device, &se, nullptr, &renderSem);
        VkFenceCreateInfo fe{};
        fe.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fe.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        vkCreateFence(device, &fe, nullptr, &fence);
        return true;
    }

    void importBuffer(AHardwareBuffer* buf) {
        VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
        fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID props{};
        props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        props.pNext = &fmtProps;
        if (pfnGetAhbProps(device, buf, &props) != VK_SUCCESS) { LOGE("getAhbProps fail"); return; }
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(buf, &desc);
        int bw = (int)desc.width;
        int bh = (int)desc.height;
        VkFormat fmt = fmtProps.format;
        VkImageUsageFlags usage = fmtProps.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                ? VK_IMAGE_USAGE_SAMPLED_BIT : VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if (!usage) usage = VK_IMAGE_USAGE_SAMPLED_BIT;
        (void)bw; (void)bh; (void)usage;
        if (fmt == VK_FORMAT_UNDEFINED) { LOGE("AHB undefined format"); return; }
        LOGI("AHB fmt=%d w=%d h=%d", (int)fmt, bw, bh);

        if (importedView) vkDestroyImageView(device, importedView, nullptr);
        if (importedImage) vkDestroyImage(device, importedImage, nullptr);
        if (importedMemory) vkFreeMemory(device, importedMemory, nullptr);
        importedView = VK_NULL_HANDLE; importedImage = VK_NULL_HANDLE; importedMemory = VK_NULL_HANDLE;
        hasImage = false;

        VkExternalMemoryImageCreateInfo ext{};
        ext.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ici.pNext = &ext;
        ici.imageType = VK_IMAGE_TYPE_2D;
        ici.format = fmt;
        ici.extent = { (uint32_t)bw, (uint32_t)bh, 1 };
        ici.mipLevels = 1;
        ici.arrayLayers = 1;
        ici.samples = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling = VK_IMAGE_TILING_OPTIMAL;
        ici.usage = usage;
        ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vkCreateImage(device, &ici, nullptr, &importedImage) != VK_SUCCESS) {
            LOGE("vkCreateImage import fail format=%d usage=0x%x", fmt, ici.usage);
            return;
        }
        VkImportAndroidHardwareBufferInfoANDROID imp{};
        imp.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        imp.buffer = buf;

        VkPhysicalDeviceMemoryProperties mprops;
        vkGetPhysicalDeviceMemoryProperties(phys, &mprops);
        uint32_t mt = UINT32_MAX;
        for (uint32_t i = 0; i < mprops.memoryTypeCount; i++) {
            if (!(props.memoryTypeBits & (1u << i))) continue;
            if (mprops.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) { mt = i; break; }
        }
        if (mt == UINT32_MAX)
            for (uint32_t i = 0; i < mprops.memoryTypeCount; i++) if (props.memoryTypeBits & (1u << i)) { mt = i; break; }
        if (mt == UINT32_MAX) { LOGE("no memory type"); return; }

        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.pNext = &imp;
        mai.allocationSize = props.allocationSize;
        mai.memoryTypeIndex = mt;
        if (vkAllocateMemory(device, &mai, nullptr, &importedMemory) != VK_SUCCESS) { LOGE("import alloc fail"); return; }
        vkBindImageMemory(device, importedImage, importedMemory, 0);

        VkImageViewCreateInfo ivi{};
        ivi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        ivi.image = importedImage;
        ivi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        ivi.format = fmt;
        ivi.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
        vkCreateImageView(device, &ivi, nullptr, &importedView);

        VkDescriptorImageInfo di{};
        di.sampler = sampler;
        di.imageView = importedView;
        di.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet wd{};
        wd.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wd.dstSet = descSet;
        wd.dstBinding = 0;
        wd.descriptorCount = 1;
        wd.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wd.pImageInfo = &di;
        vkUpdateDescriptorSets(device, 1, &wd, 0, nullptr);
        hasImage = true;
    }

    void renderLoop() {
        bool firstFrameNotified = false;
        while (running) {
            if (swapDirty.exchange(false)) {
                vkDeviceWaitIdle(device);
                if (!createSwapchain((int)extent.width, (int)extent.height)) LOGE("swapchain recreate fail");
                if (!createFramebuffers()) LOGE("framebuffer recreate fail");
                continue;
            }
            vkWaitForFences(device, 1, &fence, VK_TRUE, 200000000ULL);
            if (!running) break;
            vkResetFences(device, 1, &fence);
            uint32_t idx = 0;
            VkResult r = pfnAcquireNextImageKHR(device, swapchain, 200000000ULL, acquireSem, VK_NULL_HANDLE, &idx);
            if (r == VK_ERROR_OUT_OF_DATE_KHR) { vkDeviceWaitIdle(device); createSwapchain((int)extent.width, (int)extent.height); createFramebuffers(); continue; }
            if (!(r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR)) continue;
            if (!running) break;

            AHardwareBuffer* buf = nullptr;
            {
                std::lock_guard<std::mutex> lk(bufMutex);
                buf = latest;
                latest = nullptr;
            }
            if (buf) {
                importBuffer(buf);
                AHardwareBuffer_release(buf);
                if (!firstFrameNotified) { firstFrameNotified = true; notifyFirstFrame(); }
            }

            Push p;
            {
                std::lock_guard<std::mutex> lk(paramMutex);
                p = push;
            }
            p.resolution[0] = (float)extent.width;
            p.resolution[1] = (float)extent.height;

            vkResetCommandBuffer(cmdBuf, 0);
            VkCommandBufferBeginInfo cbb{};
            cbb.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            vkBeginCommandBuffer(cmdBuf, &cbb);

            if (hasImage) {
                VkImageMemoryBarrier imb{};
                imb.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                imb.srcAccessMask = 0;
                imb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                imb.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                imb.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                imb.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                imb.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                imb.image = importedImage;
                imb.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
                vkCmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &imb);
            }

            VkClearValue clear{};
            clear.color = { {0.f, 0.f, 0.f, 0.f} };
            VkRenderPassBeginInfo rp{};
            rp.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
            rp.renderPass = renderPass;
            rp.framebuffer = framebuffers[idx];
            rp.renderArea = { {0,0}, extent };
            rp.clearValueCount = 1;
            rp.pClearValues = &clear;
            vkCmdBeginRenderPass(cmdBuf, &rp, VK_SUBPASS_CONTENTS_INLINE);

            if (hasImage) {
                vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                        0, 1, &descSet, 0, nullptr);
                vkCmdPushConstants(cmdBuf, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(p), &p);
                vkCmdDraw(cmdBuf, 4, 1, 0, 0);
            }
            vkCmdEndRenderPass(cmdBuf);
            vkEndCommandBuffer(cmdBuf);

            VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            VkSubmitInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si.waitSemaphoreCount = 1;
            si.pWaitSemaphores = &acquireSem;
            si.pWaitDstStageMask = &waitStage;
            si.commandBufferCount = 1;
            si.pCommandBuffers = &cmdBuf;
            si.signalSemaphoreCount = 1;
            si.pSignalSemaphores = &renderSem;
            vkQueueSubmit(queue, 1, &si, fence);

            VkPresentInfoKHR pi{};
            pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
            pi.waitSemaphoreCount = 1;
            pi.pWaitSemaphores = &renderSem;
            pi.swapchainCount = 1;
            pi.pSwapchains = &swapchain;
            pi.pImageIndices = &idx;
            VkResult pr = pfnQueuePresentKHR(queue, &pi);
            if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) swapDirty = true;
        }
    }

    void destroy() {
        running = false;
        if (thread.joinable()) thread.join();
        if (!device) {
            if (window) { ANativeWindow_release(window); window = nullptr; }
            return;
        }
        vkDeviceWaitIdle(device);
        AHardwareBuffer* buf;
        {
            std::lock_guard<std::mutex> lk(bufMutex);
            buf = latest;
            latest = nullptr;
        }
        if (buf) AHardwareBuffer_release(buf);
        if (importedView) vkDestroyImageView(device, importedView, nullptr);
        if (importedImage) vkDestroyImage(device, importedImage, nullptr);
        if (importedMemory) vkFreeMemory(device, importedMemory, nullptr);
        if (fence) vkDestroyFence(device, fence, nullptr);
        if (renderSem) vkDestroySemaphore(device, renderSem, nullptr);
        if (acquireSem) vkDestroySemaphore(device, acquireSem, nullptr);
        if (cmdBuf) vkFreeCommandBuffers(device, cmdPool, 1, &cmdBuf);
        if (cmdPool) vkDestroyCommandPool(device, cmdPool, nullptr);
        if (sampler) vkDestroySampler(device, sampler, nullptr);
        if (descPool) vkDestroyDescriptorPool(device, descPool, nullptr);
        if (descLayout) vkDestroyDescriptorSetLayout(device, descLayout, nullptr);
        if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
        if (pipelineLayout) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
        if (renderPass) vkDestroyRenderPass(device, renderPass, nullptr);
        destroySwapchain();
        vkDestroyDevice(device, nullptr);
        if (surface) pfnDestroySurfaceKHR(instance, surface, nullptr);
        vkDestroyInstance(instance, nullptr);
        if (window) ANativeWindow_release(window);
        instance = VK_NULL_HANDLE; phys = VK_NULL_HANDLE; device = VK_NULL_HANDLE;
        surface = VK_NULL_HANDLE; window = nullptr; queue = VK_NULL_HANDLE;
    }
};

} // namespace

extern "C" {

static VkRenderer* fromHandle(jlong h) { return reinterpret_cast<VkRenderer*>(h); }

JNIEXPORT jlong JNICALL
Java_dev_duofold_render_FoldViewVk_nativeInit(JNIEnv* env, jobject thiz, jobject surface, jint w, jint h) {
    if (!g_vm) env->GetJavaVM(&g_vm);
    if (g_viewRef) { env->DeleteGlobalRef(g_viewRef); g_viewRef = nullptr; }
    g_viewRef = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    if (g_viewClass) { env->DeleteGlobalRef(g_viewClass); g_viewClass = nullptr; }
    g_viewClass = (jclass)env->NewGlobalRef(cls);
    g_onFirstFrame = env->GetMethodID(cls, "nativeOnFirstFrame", "()V");
    auto* r = new VkRenderer();
    if (!r->init(env, surface, (int)w, (int)h)) {
        r->destroy();
        delete r;
        return 0;
    }
    return reinterpret_cast<jlong>(r);
}

JNIEXPORT void JNICALL
Java_dev_duofold_render_FoldViewVk_nativeSubmit(JNIEnv* env, jobject, jlong handle, jobject hwBuffer) {
    auto* r = fromHandle(handle);
    if (!r || !hwBuffer) return;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, hwBuffer);
    if (!buf) return;
    AHardwareBuffer_acquire(buf);
    AHardwareBuffer* old = nullptr;
    {
        std::lock_guard<std::mutex> lk(r->bufMutex);
        old = r->latest;
        r->latest = buf;
    }
    if (old) AHardwareBuffer_release(old);
}

JNIEXPORT void JNICALL
Java_dev_duofold_render_FoldViewVk_nativeSetParams(JNIEnv* env, jobject, jlong handle,
        jfloat resX, jfloat resY, jfloat angleX, jfloat angleY,
        jfloat sizeX, jfloat sizeY, jfloat intensity, jfloat blurScale, jfloat stretch) {
    auto* r = fromHandle(handle);
    if (!r) return;
    Push p = r->push;
    p.resolution[0] = resX; p.resolution[1] = resY;
    p.angles[0] = angleX; p.angles[1] = angleY;
    p.sizePoints[0] = sizeX; p.sizePoints[1] = sizeY;
    p.intensity = intensity; p.blurScale = blurScale; p.stretch = stretch;
    {
        std::lock_guard<std::mutex> lk(r->paramMutex);
        r->push = p;
    }
}

JNIEXPORT void JNICALL
Java_dev_duofold_render_FoldViewVk_nativeSurfaceChanged(JNIEnv*, jobject, jlong handle, jint, jint) {
    auto* r = fromHandle(handle);
    if (r) r->swapDirty = true;
}

JNIEXPORT void JNICALL
Java_dev_duofold_render_FoldViewVk_nativeRelease(JNIEnv* env, jobject, jlong handle) {
    auto* r = fromHandle(handle);
    if (r) {
        r->destroy();
        delete r;
    }
    if (g_viewRef) { env->DeleteGlobalRef(g_viewRef); g_viewRef = nullptr; }
    if (g_viewClass) { env->DeleteGlobalRef(g_viewClass); g_viewClass = nullptr; }
    g_onFirstFrame = nullptr;
}

} // extern "C"
