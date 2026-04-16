#include <jni.h>
#include <vector>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

namespace {

void applyCustomKernel(cv::Mat& rgba) {
    if (rgba.empty() || rgba.type() != CV_8UC4 || rgba.rows < 3 || rgba.cols < 3) {
        return;
    }

    static cv::Mat sharpened;
    static cv::Mat gray;
    static cv::Mat gradX;
    static cv::Mat gradY;
    static cv::Mat absGradX;
    static cv::Mat absGradY;
    static cv::Mat edgeStrength;
    static cv::Mat edgeMask;

    static const cv::Mat sharpenKernel = (cv::Mat_<float>(3, 3) <<
            0.0f, -1.0f, 0.0f,
            -1.0f, 5.0f, -1.0f,
            0.0f, -1.0f, 0.0f);

    cv::filter2D(rgba, sharpened, rgba.depth(), sharpenKernel);

    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    cv::Sobel(gray, gradX, CV_16S, 1, 0, 3);
    cv::Sobel(gray, gradY, CV_16S, 0, 1, 3);
    cv::convertScaleAbs(gradX, absGradX);
    cv::convertScaleAbs(gradY, absGradY);
    cv::addWeighted(absGradX, 0.5, absGradY, 0.5, 0.0, edgeStrength);
    cv::threshold(edgeStrength, edgeMask, 70, 255, cv::THRESH_BINARY);

    sharpened.copyTo(rgba);
    rgba.setTo(cv::Scalar(0, 255, 40, 255), edgeMask);

    cv::putText(
            rgba,
            "C++ kernel",
            cv::Point(24, 64),
            cv::FONT_HERSHEY_SIMPLEX,
            1.0,
            cv::Scalar(255, 255, 255, 255),
            2
    );
}

cv::Mat* createSideBySidePanorama(const std::vector<cv::Mat>& images) {
    std::vector<cv::Mat> validImages;
    validImages.reserve(images.size());

    int commonHeight = 0;
    for (const cv::Mat& image : images) {
        if (image.empty()) {
            continue;
        }

        if (commonHeight == 0 || image.rows < commonHeight) {
            commonHeight = image.rows;
        }
        validImages.push_back(image);
    }

    if (validImages.empty() || commonHeight <= 0) {
        return nullptr;
    }

    std::vector<cv::Mat> resizedImages;
    std::vector<cv::Mat> panoramaParts;
    resizedImages.reserve(validImages.size());
    panoramaParts.reserve(validImages.size());

    for (const cv::Mat& image : validImages) {
        if (image.rows == commonHeight) {
            panoramaParts.push_back(image);
            continue;
        }

        int scaledWidth = std::max(1, image.cols * commonHeight / image.rows);
        resizedImages.emplace_back();
        cv::resize(image, resizedImages.back(), cv::Size(scaledWidth, commonHeight));
        panoramaParts.push_back(resizedImages.back());
    }

    auto* result = new cv::Mat();
    cv::hconcat(panoramaParts, *result);
    return result;
}

}

extern "C" JNIEXPORT void JNICALL
Java_com_gregorgullwi_panorama_MainActivity_processFrame(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong rgbaMatAddr) {
    auto& rgba = *reinterpret_cast<cv::Mat*>(rgbaMatAddr);
    applyCustomKernel(rgba);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gregorgullwi_panorama_MainActivity_createPanorama(
        JNIEnv* env,
        jobject /* this */,
        jlongArray rgbaMatAddrs) {
    if (rgbaMatAddrs == nullptr) {
        return 0;
    }

    jsize addressCount = env->GetArrayLength(rgbaMatAddrs);
    if (addressCount <= 0) {
        return 0;
    }

    std::vector<jlong> addresses(static_cast<size_t>(addressCount));
    env->GetLongArrayRegion(rgbaMatAddrs, 0, addressCount, addresses.data());

    std::vector<cv::Mat> images;
    images.reserve(addresses.size());
    for (jlong address : addresses) {
        if (address == 0) {
            continue;
        }

        auto& image = *reinterpret_cast<cv::Mat*>(address);
        if (image.empty()) {
            continue;
        }

        images.push_back(image);
    }

    cv::Mat* panorama = createSideBySidePanorama(images);
    return reinterpret_cast<jlong>(panorama);
}
