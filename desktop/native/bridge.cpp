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

constexpr uint32_t batch_shader[] = {
#include "batch.inc"
};
constexpr unsigned max_batch_ticks = 256;
constexpr unsigned max_batch_drives = 4096;
constexpr unsigned schedule_words = 3 + max_batch_ticks + max_batch_drives * 2;

struct Circuit {
    std::unique_ptr<terni::ComputeRunner> runner;
    std::string device;
    std::vector<jint> signals;
    uint32_t result_count = 0;
    uint32_t readback = 0;
    std::vector<jint> schedule;
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

terni::DeviceInfo select_device(bool allow_software) {
    const int selected = terni::pick_device();
    for (const auto& candidate : terni::VulkanContext::list_devices()) {
        if (int(candidate.index) != selected || !candidate.usable) continue;
        if (candidate.cpu() && !allow_software)
            throw std::runtime_error("Only software Vulkan is available");
        return candidate;
    }
    throw std::runtime_error("No usable Vulkan device");
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

        const auto device = select_device(allow_software);
        auto sim = std::make_unique<Circuit>();
        sim->device = device.type + " " + device.name;
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
            device.index, false, "", buffers, stages, reduce ? std::vector<uint32_t>{3} : std::vector<uint32_t>{}, false, constants);
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

JNIEXPORT jlong JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_createBatch(
    JNIEnv* env, jobject, jintArray gates, jintArray pins, jintArray writes,
    jint state_count, jboolean allow_software) {
    return checked<jlong>(env, [&] {
        const auto gate_rows = copy(env, gates);
        auto input_rows = copy(env, pins);
        const auto destinations = copy(env, writes);
        const size_t gate_count = gate_rows.size() / 5;
        const size_t input_count = input_rows.size() / 4;
        if (gate_count < 1 || gate_count > 256 || gate_rows.size() % 5 || input_rows.size() % 4 ||
            destinations.size() != gate_rows.size() + input_count ||
            state_count < 1 || state_count > 2048)
            throw std::invalid_argument("Invalid batch circuit dimensions");
        for (size_t i = 0; i < gate_rows.size(); ++i) {
            const jint value = gate_rows[i];
            if (i % 5 == 0 ? value < 0 || value > 5 : value < -1 || value >= jint(input_count))
                throw std::invalid_argument("Invalid batch gate input");
        }
        for (jint pin : input_rows)
            if (pin < -1 || pin >= state_count) throw std::invalid_argument("Invalid batch pin");
        for (size_t i = 0; i < destinations.size(); ++i) {
            const bool required = i >= gate_rows.size() || i % 5 == 0;
            if (destinations[i] < (required ? 0 : -1) || destinations[i] >= state_count)
                throw std::invalid_argument("Invalid batch destination");
        }
        if (input_rows.empty()) input_rows.resize(4, -1);

        const auto device = select_device(allow_software);
        auto sim = std::make_unique<Circuit>();
        sim->result_count = uint32_t(state_count);
        sim->signals.resize(size_t(state_count) + input_count);
        sim->schedule.resize(schedule_words);
        const std::vector<jint> output(state_count);
        const uint64_t result_bytes = output.size() * sizeof(jint);
        const std::vector<terni::ComputeBufferSpec> buffers{
            {"gates", gate_rows.data(), gate_rows.size() * sizeof(jint)},
            {"pins", input_rows.data(), input_rows.size() * sizeof(jint)},
            {"destinations", destinations.data(), destinations.size() * sizeof(jint)},
            {"initial", sim->signals.data(), sim->signals.size() * sizeof(jint)},
            {"schedule", sim->schedule.data(), sim->schedule.size() * sizeof(jint)},
            {"results", output.data(), result_bytes}
        };
        const std::vector<terni::ComputeStageSpec> stages{
            {"batch", 1, {std::begin(batch_shader), std::end(batch_shader)}, {}}
        };
        const std::vector<terni::ComputeSpecConstant> constants{
            {2, uint32_t(gate_count)}, {3, uint32_t(state_count)}
        };
        sim->runner = std::make_unique<terni::ComputeRunner>(
            device.index, false, "", buffers, stages, std::vector<uint32_t>{}, false, constants);
        sim->readback = sim->runner->add_tick_readback(5, result_bytes);
        return reinterpret_cast<jlong>(sim.release());
    });
}

JNIEXPORT jdouble JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_evaluateBatch(
    JNIEnv* env, jobject, jlong handle, jintArray initial, jintArray commands, jintArray results,
    jboolean resume, jboolean publish) {
    return checked<jdouble>(env, [&] {
        auto& sim = circuit(handle);
        const auto size = env->GetArrayLength(commands);
        if (size < 2 || size_t(size) > sim.schedule.size() ||
            size_t(env->GetArrayLength(initial)) != sim.signals.size() ||
            uint32_t(env->GetArrayLength(results)) != sim.result_count)
            throw std::invalid_argument("Invalid batch array length");
        env->GetIntArrayRegion(commands, 0, size, sim.schedule.data());
        if (!resume) env->GetIntArrayRegion(initial, 0, jsize(sim.signals.size()), sim.signals.data());
        if (env->ExceptionCheck()) return 0.0;
        const auto ticks = sim.schedule[0];
        const auto drives = sim.schedule[1];
        if (ticks < 1 || ticks > jint(max_batch_ticks) || drives < 0 || drives > jint(max_batch_drives) ||
            size != (drives == 0 ? 2 : 3 + ticks + 2 * drives))
            throw std::invalid_argument("Invalid batch schedule");
        if (drives > 0) {
            if (sim.schedule[2] != 0 || sim.schedule[2 + ticks] != drives)
                throw std::invalid_argument("Invalid batch schedule offsets");
            for (int i = 0; i < ticks; ++i)
                if (sim.schedule[2 + i] > sim.schedule[3 + i])
                    throw std::invalid_argument("Invalid batch schedule order");
            for (int i = 3 + ticks; i < size; i += 2)
                if (sim.schedule[i] < 0 || uint32_t(sim.schedule[i]) >= sim.result_count ||
                    (sim.schedule[i + 1] != 2 && sim.schedule[i + 1] != 3))
                    throw std::invalid_argument("Invalid batch schedule drive");
        }
        if (resume) sim.schedule[0] |= 0x10000;
        else sim.runner->queue_write(3, sim.signals.data(), sim.signals.size() * sizeof(jint));
        sim.runner->queue_write(4, sim.schedule.data(), uint64_t(size) * sizeof(jint));
        sim.runner->set_tick_readback_bytes(sim.readback, publish ? sim.result_count * sizeof(jint) : 0);
        sim.runner->tick();
        if (publish) env->SetIntArrayRegion(results, 0, jsize(sim.result_count),
                                           static_cast<const jint*>(sim.runner->tick_readback(sim.readback)));
        return sim.runner->last_tick_gpu_ms();
    });
}

JNIEXPORT void JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_readBatch(
    JNIEnv* env, jobject, jlong handle, jintArray results) {
    checked<bool>(env, [&] {
        auto& sim = circuit(handle);
        if (uint32_t(env->GetArrayLength(results)) != sim.result_count)
            throw std::invalid_argument("Invalid batch snapshot length");
        // Reuse the input staging allocation. Only the state prefix is read.
        sim.runner->read(5, sim.signals.data(), sim.result_count * sizeof(jint));
        env->SetIntArrayRegion(results, 0, jsize(sim.result_count), sim.signals.data());
        return true;
    });
}

JNIEXPORT void JNICALL Java_me_wieku_circuits_desktop_simulation_TerniNative_destroy(
    JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Circuit*>(handle);
}
}
