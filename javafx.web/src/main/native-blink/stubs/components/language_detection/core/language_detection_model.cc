// Stubbed — jux-engine build. TFLite language detection removed.
#include "components/language_detection/core/language_detection_model.h"

#include "base/functional/callback.h"

namespace language_detection {

Prediction TopPrediction(const std::vector<Prediction>& predictions) {
  if (predictions.empty()) return Prediction("und", 0.0f);
  return *std::max_element(predictions.begin(), predictions.end());
}

LanguageDetectionModel::LanguageDetectionModel() = default;
LanguageDetectionModel::~LanguageDetectionModel() = default;

std::vector<Prediction> LanguageDetectionModel::Predict(
    std::u16string_view) const { return {}; }
std::vector<Prediction> LanguageDetectionModel::PredictWithScan(
    std::u16string_view) const { return {}; }
Prediction LanguageDetectionModel::PredictTopLanguageWithSamples(
    std::u16string_view) const { return Prediction("und", 0.0f); }
void LanguageDetectionModel::UpdateWithFile(base::File) {}
void LanguageDetectionModel::UpdateWithFileAsync(
    base::File, base::OnceClosure callback) {
  if (callback) std::move(callback).Run();
}
bool LanguageDetectionModel::IsAvailable() const { return false; }
int64_t LanguageDetectionModel::GetModelSize() const { return 0; }
void LanguageDetectionModel::AddOnModelLoadedCallback(ModelLoadedCallback) {}
std::string LanguageDetectionModel::GetModelVersion() const { return {}; }

}  // namespace language_detection
