#ifndef TFLITE_SUPPORT_TASK_CORE_CATEGORY_H_
#define TFLITE_SUPPORT_TASK_CORE_CATEGORY_H_
#include <string>
namespace tflite { namespace task { namespace core {
struct Category { int index; float score; std::string class_name; std::string display_name; };
}}}
#endif
