#include <jni.h>

#include <opus.h>

#include <array>
#include <cstdint>
#include <mutex>
#include <new>
#include <string>
#include <vector>

namespace {

constexpr int kOpusApplicationAudio = OPUS_APPLICATION_AUDIO;
constexpr int kOpusOk = OPUS_OK;
constexpr jint kInputFrameSamples = 960;
constexpr jint kMaxEncodedBytes = 4000;
constexpr int kMaxPacketDurationMs = 120;

struct DecoderHandle {
    OpusDecoder* decoder;
    int sampleRate;
};

void throwIllegalState(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

bool isValidSampleRate(jint sampleRate) {
    return sampleRate == 8000 || sampleRate == 12000 || sampleRate == 16000
           || sampleRate == 24000 || sampleRate == 48000;
}

jshortArray emptyShortArray(JNIEnv* env) {
    return env->NewShortArray(0);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_xiaozhi_x08c_OpusCodec_nativeCreateEncoder(
        JNIEnv* env, jclass, jint sampleRate, jint channels) {
    if (sampleRate != 16000 || channels != 1) {
        throwIllegalState(env, "Opus 编码器仅支持 16000 Hz 单声道");
        return 0;
    }

    int error = kOpusOk;
    OpusEncoder* encoder = opus_encoder_create(sampleRate, channels,
                                               kOpusApplicationAudio, &error);
    if (encoder == nullptr || error != kOpusOk) {
        throwIllegalState(env, "opus_encoder_create 失败，错误码 "
                               + std::to_string(error));
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(encoder));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xiaozhi_x08c_OpusCodec_nativeCreateDecoder(
        JNIEnv* env, jclass, jint sampleRate, jint channels) {
    if (!isValidSampleRate(sampleRate) || channels != 1) {
        throwIllegalState(env, "无效的 Opus 解码器采样率或声道数");
        return 0;
    }

    int error = kOpusOk;
    OpusDecoder* decoder = opus_decoder_create(sampleRate, channels, &error);
    if (decoder == nullptr || error != kOpusOk) {
        throwIllegalState(env, "opus_decoder_create 失败，错误码 "
                               + std::to_string(error));
        return 0;
    }

    DecoderHandle* handle = new (std::nothrow) DecoderHandle{decoder, sampleRate};
    if (handle == nullptr) {
        opus_decoder_destroy(decoder);
        throwIllegalState(env, "无法分配 Opus 解码器句柄");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xiaozhi_x08c_OpusCodec_nativeEncode(
        JNIEnv* env, jclass, jlong encoderHandle, jshortArray pcm) {
    OpusEncoder* encoder = reinterpret_cast<OpusEncoder*>(
            static_cast<intptr_t>(encoderHandle));
    if (encoder == nullptr) {
        throwIllegalState(env, "Opus 编码器句柄无效");
        return nullptr;
    }
    if (pcm == nullptr || env->GetArrayLength(pcm) != kInputFrameSamples) {
        throwIllegalState(env, "Opus 编码输入必须是 960 个 PCM16 样本");
        return nullptr;
    }

    std::array<jshort, kInputFrameSamples> input{};
    env->GetShortArrayRegion(pcm, 0, kInputFrameSamples, input.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    std::array<unsigned char, kMaxEncodedBytes> output{};
    const int encodedBytes = opus_encode(
            encoder, reinterpret_cast<const opus_int16*>(input.data()),
            kInputFrameSamples, output.data(),
            static_cast<opus_int32>(output.size()));
    if (encodedBytes < 0 || encodedBytes > static_cast<int>(output.size())) {
        throwIllegalState(env, "opus_encode 失败，错误码 "
                               + std::to_string(encodedBytes));
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(encodedBytes);
    if (result == nullptr) {
        return nullptr;
    }
    if (encodedBytes > 0) {
        env->SetByteArrayRegion(result, 0, encodedBytes,
                                reinterpret_cast<const jbyte*>(output.data()));
    }
    return result;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_xiaozhi_x08c_OpusCodec_nativeDecode(
        JNIEnv* env, jclass, jlong decoderAddress, jbyteArray data) {
    DecoderHandle* handle = reinterpret_cast<DecoderHandle*>(
            static_cast<intptr_t>(decoderAddress));
    if (handle == nullptr || handle->decoder == nullptr) {
        throwIllegalState(env, "Opus 解码器句柄无效");
        return nullptr;
    }
    if (data == nullptr) {
        throwIllegalState(env, "Opus 输入数据不能为 null");
        return nullptr;
    }

    const jsize packetLength = env->GetArrayLength(data);
    if (packetLength == 0) {
        return emptyShortArray(env);
    }

    // Opus packets contain at most 120 ms of audio. Supplying the exact upper
    // bound for the configured output rate prevents decoder buffer overruns.
    // Allocate before acquiring the Java array so every acquired array has a
    // straight-line, exception-free path to ReleaseByteArrayElements.
    const int outputCapacity = handle->sampleRate * kMaxPacketDurationMs / 1000;
    std::vector<opus_int16> pcm(static_cast<size_t>(outputCapacity));

    jbyte* packet = env->GetByteArrayElements(data, nullptr);
    if (packet == nullptr) {
        return nullptr;
    }
    const int decodedSamples = opus_decode(
            handle->decoder, reinterpret_cast<const unsigned char*>(packet),
            static_cast<opus_int32>(packetLength), pcm.data(), outputCapacity,
            0);
    env->ReleaseByteArrayElements(data, packet, JNI_ABORT);

    if (decodedSamples < 0 || decodedSamples > outputCapacity) {
        throwIllegalState(env, "opus_decode 失败，错误码 "
                               + std::to_string(decodedSamples));
        return nullptr;
    }

    jshortArray result = env->NewShortArray(decodedSamples);
    if (result == nullptr) {
        return nullptr;
    }
    if (decodedSamples > 0) {
        env->SetShortArrayRegion(result, 0, decodedSamples,
                                 reinterpret_cast<const jshort*>(pcm.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xiaozhi_x08c_OpusCodec_nativeDestroyEncoder(
        JNIEnv*, jclass, jlong encoderHandle) {
    OpusEncoder* encoder = reinterpret_cast<OpusEncoder*>(
            static_cast<intptr_t>(encoderHandle));
    if (encoder != nullptr) {
        opus_encoder_destroy(encoder);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_xiaozhi_x08c_OpusCodec_nativeDestroyDecoder(
        JNIEnv*, jclass, jlong decoderAddress) {
    DecoderHandle* handle = reinterpret_cast<DecoderHandle*>(
            static_cast<intptr_t>(decoderAddress));
    if (handle == nullptr) {
        return;
    }
    if (handle->decoder != nullptr) {
        opus_decoder_destroy(handle->decoder);
        handle->decoder = nullptr;
    }
    delete handle;
}
