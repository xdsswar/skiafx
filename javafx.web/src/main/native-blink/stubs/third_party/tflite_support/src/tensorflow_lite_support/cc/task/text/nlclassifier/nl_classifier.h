#ifndef TFLITE_SUPPORT_TASK_TEXT_NL_CLASSIFIER_H_
#define TFLITE_SUPPORT_TASK_TEXT_NL_CLASSIFIER_H_
#include <memory>
#include <string>
#include <vector>
#include "tensorflow_lite_support/cc/task/core/category.h"
namespace tflite { namespace task { namespace text { namespace nlclassifier {
class NLClassifier {
 public:
  virtual ~NLClassifier() = default;
  std::vector<core::Category> Classify(const std::string&) { return {}; }
};
}}}}
#endif
