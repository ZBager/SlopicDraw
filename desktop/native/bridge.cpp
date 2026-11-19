#include <jni.h>
#include <terni/compute_runner.hpp>
#include <terni/vk_context.hpp>

#include <chrono>
#include <iterator>
#include <locale>
#include <sstream>
#include <memory>
#include <stdexcept>

namespace {
constexpr uint32_t shader[] = {
#include "evaluate.inc"
};

struct Circuit {
    std::unique_ptr<terni::ComputeRunner> runner;
    std::string device;
    std::vector<jint> signals;
    uint32_t result_count = 0;
    uint32_t readback = 0;
    uint64_t ticks = 0;
    double native_ms = 0;
    double submission_ms = 0;
    double device_ms = 0;
    double compute_ms = 0;
};

void throw_java(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    if (auto cls = env->FindClass("java/lang/IllegalStateException"))
        env->ThrowNew(cls, message);
}

template<class Result, class Call>
Result checked(JNIEnv* env, Call call) {
    try {
        return call();
    } catch (const std::exception& error) {
        throw_java(env, error.what());
    } catch (...) {
        throw_java(env, "Unknown Terni error");
    }
    return {};
}

std::vector<jint> copy(JNIEnv* env, jintArray array) {
    std::vector<jint> values(env->GetArrayLength(array));
    if (!values.empty()) env->GetIntArrayRegion(array, 0, jsize(values.size()), values.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read topology");
    return values;
}

Circuit& circuit(jlong handle) {
    if (!handle) throw std::invalid_argument("No Terni circuit");
    return *reinterpret_cast<Circuit*>(handle);
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_create(
    JNIEnv* env, jobject, jintArray gates, jintArray pins, jint net_count,
    jintArray writes, jint result_count, jboolean allow_software) {
    return checked<jlong>(env, [&] {
        const auto gate_rows = copy(env, gates);
        auto input_rows = copy(env, pins);
        if (gate_rows.empty() || gate_rows.size() % 5 || input_rows.size() % 4 || net_count < 1)
            throw std::invalid_argument("Invalid circuit dimensions");

        const uint32_t gate_count = uint32_t(gate_rows.size() / 5);
        const size_t input_count = input_rows.size() / 4;
        auto destinations = copy(env, writes);
        const bool reduce = !destinations.empty();
        if (result_count < 1 || (reduce ? destinations.size() != gate_rows.size() + input_count
                                        : uint32_t(result_count) != gate_count))
            throw std::invalid_argument("Invalid result dimensions");
        for (size_t i = 0; i < destinations.size(); ++i) {
            const jint dest = destinations[i];
            const bool required = i >= gate_rows.size() || i % 5 == 0;
            if (dest < (required ? 0 : -1) || dest >= result_count)
                throw std::invalid_argument("Invalid destination index");
        }
        if (!reduce) destinations.resize(1);

        for (size_t row = 0; row < gate_rows.size(); row += 5) {
            if (gate_rows[row] < 0 || gate_rows[row] > 5)
                throw std::invalid_argument("Invalid gate type");
            for (size_t pin = 1; pin <= 4; ++pin) {
                const jint input = gate_rows[row + pin];
                if (input < -1 || (input >= 0 && size_t(input) >= input_count))
                    throw std::invalid_argument("Invalid gate input index");
            }
        }
        for (jint net : input_rows)
            if (net < 0 || net >= net_count) throw std::invalid_argument("Invalid net index");
        if (input_rows.empty()) input_rows.resize(4);

        const int selected = terni::pick_device();
        const auto devices = terni::VulkanContext::list_devices();
        const terni::DeviceInfo* device = nullptr;
        for (const auto& candidate : devices)
            if (int(candidate.index) == selected && candidate.usable) device = &candidate;
        if (!device) throw std::runtime_error("No usable Vulkan device");
        if (device->cpu() && !allow_software)
            throw std::runtime_error("Only software Vulkan is available");

        auto sim = std::make_unique<Circuit>();
        sim->device = device->type + " " + device->name;
        sim->result_count = uint32_t(result_count);
        sim->signals.resize(size_t(net_count) + input_count);
        const uint64_t result_bytes = uint64_t(sim->result_count) * sizeof(jint);
        const std::vector<jint> initial_results(sim->result_count);
        const std::vector<terni::ComputeBufferSpec> buffers{
            {"gates", gate_rows.data(), gate_rows.size() * sizeof(jint)},
            {"pins", input_rows.data(), input_rows.size() * sizeof(jint)},
            {"signals", sim->signals.data(), sim->signals.size() * sizeof(jint)},
            {"results", initial_results.data(), result_bytes},
            {"destinations", destinations.data(), destinations.size() * sizeof(jint)}
        };
        const std::vector<terni::ComputeStageSpec> stages{
            {"evaluate", gate_count, {std::begin(shader), std::end(shader)}, {}}
        };
        const std::vector<terni::ComputeSpecConstant> constants{
            {2, gate_count}, {3, uint32_t(net_count)}, {4, uint32_t(reduce)}
        };
        sim->runner = std::make_unique<terni::ComputeRunner>(
            device->index, false, "", buffers, stages, reduce ? std::vector<uint32_t>{3} : std::vector<uint32_t>{}, false, constants);
        sim->readback = sim->runner->add_tick_readback(3, result_bytes);
        return reinterpret_cast<jlong>(sim.release());
    });
}

JNIEXPORT jstring JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_describe(
    JNIEnv* env, jobject, jlong handle) {
    return checked<jstring>(env, [&] { return env->NewStringUTF(circuit(handle).device.c_str()); });
}

JNIEXPORT jstring JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_report(
    JNIEnv* env, jobject, jlong handle) {
    return checked<jstring>(env, [&] {
        const auto& sim = circuit(handle);
        std::ostringstream out;
        out.imbue(std::locale::classic());
        out << sim.runner->report() << "\n      "
            << sim.signals.size() * sizeof(jint) << " bytes uploaded, "
            << sim.result_count * sizeof(jint) << " bytes read back per tick";
        if (sim.ticks) {
            out << ", means: " << sim.native_ms / sim.ticks << " ms native call, "
                << sim.submission_ms / sim.ticks << " ms submission and wait, "
                << sim.device_ms / sim.ticks << " ms device elapsed, "
                << sim.compute_ms / sim.ticks << " ms compute";
        }
        return env->NewStringUTF(out.str().c_str());
    });
}

JNIEXPORT jdouble JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_evaluate(
    JNIEnv* env, jobject, jlong handle, jintArray signals, jintArray results, jboolean check_order) {
    return checked<jdouble>(env, [&] {
        auto& sim = circuit(handle);
        const auto start = std::chrono::steady_clock::now();
        if (size_t(env->GetArrayLength(signals)) != sim.signals.size() ||
            uint32_t(env->GetArrayLength(results)) != sim.result_count)
            throw std::invalid_argument("Invalid signal or result array length");
        env->GetIntArrayRegion(signals, 0, jsize(sim.signals.size()), sim.signals.data());
        if (env->ExceptionCheck()) return 0.0;
        sim.runner->queue_write(2, sim.signals.data(), sim.signals.size() * sizeof(jint));
        if (check_order) {
            const auto diffs = sim.runner->tick_checked({true}, {5, 4, 1, 1, 1});
            if (!diffs.empty()) throw std::runtime_error("Terni shader results differ by execution order");
        } else {
            sim.runner->tick();
        }
        env->SetIntArrayRegion(results, 0, jsize(sim.result_count),
                               static_cast<const jint*>(sim.runner->tick_readback(sim.readback)));
        const double compute = sim.runner->last_tick_gpu_ms();
        if (!check_order) {
            ++sim.ticks;
            sim.native_ms += std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - start).count();
            sim.submission_ms += sim.runner->last_tick_ms();
            sim.device_ms += sim.runner->last_tick_device_ms();
            sim.compute_ms += compute;
        }
        return compute;
    });
}

JNIEXPORT void JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_destroy(
    JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Circuit*>(handle);
}
}
