// Copyright 2026 Xtreme Software Solutions. All rights reserved.
// JuxDomClientImpl — browser-side receiver for DOM events forwarded from
// the renderer. Writes events into the Java-visible event ring buffer.

#ifndef JUX_DOM_CLIENT_IMPL_H_
#define JUX_DOM_CLIENT_IMPL_H_

#include <cstdint>
#include <string>
#include <vector>

#include "base/memory/raw_ptr.h"
#include "jux/jux_dom.mojom.h"
#include "mojo/public/cpp/bindings/receiver.h"

namespace jux {

class EventWriter;
namespace ipc {
class SharedMemoryChannel;
}

// Implements the browser side of the DOM Mojo pipe. There is one instance
// per WebContents (created when the content is set up). It receives DOM
// events fired in the renderer and translates them into ring-buffer
// events for Java.
class JuxDomClientImpl : public mojom::JuxDomClient {
 public:
  JuxDomClientImpl(EventWriter* writer, ipc::SharedMemoryChannel* channel);
  ~JuxDomClientImpl() override;

  JuxDomClientImpl(const JuxDomClientImpl&) = delete;
  JuxDomClientImpl& operator=(const JuxDomClientImpl&) = delete;

  // Binds this impl to a pending receiver. The receiver is owned
  // internally; calling this twice replaces the prior binding.
  void Bind(mojo::PendingReceiver<mojom::JuxDomClient> receiver);

  // mojom::JuxDomClient:
  void OnDomEvent(int64_t node_id,
                  const std::string& event_type,
                  const std::vector<uint8_t>& payload) override;
  void OnScriptedPrint(bool user_initiated) override;
  void OnMutations(
      std::vector<mojom::MutationRecordPtr> records) override;
  void OnHitSpotRects(
      std::vector<mojom::HitSpotRectPtr> rects) override;
  void OnContextMenu(double x, double y, uint32_t flags,
                     const std::string& link, const std::string& src,
                     const std::string& selection) override;
  void OnTooltipChanged(const std::string& text) override;
  void OnSelectPopup(uint32_t popup_id, uint32_t flags, int32_t selected_index,
                     double x, double y, double w, double h,
                     std::vector<mojom::JuxSelectItemPtr> items) override;
  void OnColorChooser(uint32_t chooser_id, uint32_t initial_rgba,
                      const std::vector<uint32_t>& suggestions) override;
  void OnJavaCall(int32_t java_id, int32_t call_id, const std::string& name,
                  uint32_t argc,
                  const std::vector<uint8_t>& args) override;

 private:
  raw_ptr<EventWriter> writer_;
  raw_ptr<ipc::SharedMemoryChannel> channel_;
  mojo::Receiver<mojom::JuxDomClient> receiver_{this};
  // Monotonic id stamped on each context-menu request. The menu is rendered in
  // the foreground JavaFX process, so this id is informational only (no engine
  // round-trip correlates back to it).
  uint32_t next_menu_id_ = 1;
};

}  // namespace jux

#endif  // JUX_DOM_CLIENT_IMPL_H_
