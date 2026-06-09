/*
 * Copyright (c) 2026, skia-fx. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The skia-fx project
 * designates this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 */
package com.sun.webkit.blink;

/**
 * Engine→Java event type IDs. Must match the engine's {@code jux_event_types.h}
 * exactly (Java is the canonical source of truth).
 *
 * <p>Events are written to the event ring buffer by the engine and read by the
 * single {@link EventPump} reader thread. Payload formats are documented per
 * constant where the pump decodes them.
 *
 * <p>Internal; never exported from {@code javafx.web}.
 */
final class NativeEventType {

    private NativeEventType() { }

    // System (0x0001–0x000F)
    static final int ENGINE_READY = 0x0001;
    static final int ENGINE_ERROR = 0x0002;
    static final int ENGINE_SHUTDOWN = 0x0003;
    /** [windowId:4][terminationStatus:4] */
    static final int RENDER_PROCESS_GONE = 0x0004;
    /** [windowId:4] */
    static final int GPU_PROCESS_CRASHED = 0x0005;

    // Mouse input (0x0100–0x010F)
    /**
     * The hovered element's cursor changed. The engine has no OS window to apply
     * it to (off-screen rendering), so it forwards the resolved type and Java sets
     * it on the WebView node. Payload: {@code [windowId:4][cursorType:4]} where
     * cursorType is a {@code com.sun.webkit.CursorManager} constant (POINTER=0,
     * HAND=2, TEXT=4, …) resolved by the engine's cursor-type mapping.
     */
    static final int CURSOR_CHANGED = 0x0108;

    // Document (0x0200–0x020F)
    static final int DOC_LOADING = 0x0200;
    static final int DOC_INTERACTIVE = 0x0201;
    static final int DOC_READY = 0x0202;
    static final int DOC_CONTENT_LOADED = 0x0203;
    /** [windowId:4][titleLen:4][utf8Title:N] */
    static final int DOC_TITLE_CHANGED = 0x0204;
    /** [windowId:4][urlLen:4][utf8Url:N] */
    static final int DOC_NAVIGATION = 0x0205;
    /** [windowId:4] — first non-empty paint / load-stop backstop */
    static final int DOC_READY_TO_SHOW = 0x0206;
    /**
     * Session-history snapshot, emitted after each committed main-frame
     * navigation. The full entry list rides inline; because it can exceed a
     * 248-byte event slot (URLs get long), the engine sends it via
     * {@code WriteEventLarge}, which {@link EventRingBuffer} transparently
     * reassembles from multiple ring slots (see {@code MemoryLayout.EVT_CONT_FLAG}).
     * Payload: {@code [windowId:4][currentIndex:4(int32)][count:4]
     *           {[urlLen:4][utf8Url:N][titleLen:4][utf8Title:N]}…}.
     */
    static final int HISTORY_STATE = 0x0207;
    /**
     * Serialized session snapshot for crash recovery — the engine's full
     * NavigationController + PageState (URL, scroll position, form values,
     * back/forward history), emitted periodically and after each navigation.
     * Opaque engine-serialized bytes; Java just stores the latest and replays it
     * via {@link CommandType#RESTORE_SESSION} after an engine respawn. Rides
     * {@code WriteEventLarge} (the blob can exceed a slot). Payload:
     * {@code [windowId:4][blob:N]}.
     */
    static final int SESSION_STATE = 0x0208;

    // JavaScript bridge (0x0210–0x021F)
    /** [windowId:4][requestId:4][resultLen:4][utf8Result:N] */
    static final int JS_RESULT = 0x0210;
    /** [windowId:4][requestId:4][errorLen:4][utf8Error:N] */
    static final int JS_ERROR = 0x0211;
    /** [windowId:4][javaObjectId:4][nameLen:4][utf8Name:N][argc:4]{value:tagged}…
     *  — JS invoked a method on a Java object exposed via JSObject.setMember. */
    static final int JS_CALLBACK = 0x0212;
    /** [windowId:4][requestId:4][value:tagged] — typed result of any sync JS op
     *  (executeScript and every JSObject operation). Rides WriteEventLarge, so a
     *  large string/object result reassembles transparently. See JSValueCodec. */
    static final int JS_VALUE = 0x0213;

    // Print (0x0410–0x041F)
    static final int PRINT_REQUESTED = 0x0410;
    static final int PRINT_RESULT = 0x0411;

    // Off-screen render (0x0600–0x060F)
    /**
     * A new captured frame is available in the channel's data region.
     * Payload: {@code [windowId:4][bufIndex:4][width:4][height:4][stride:4]}.
     * width/height are device pixels; stride is the row stride in bytes
     * (BGRA8888, premultiplied); bufIndex selects the data-region slot (0/1).
     */
    static final int FRAME_READY = 0x0600;
    /** OSR popup frame (select/color/datalist). Pixels in a double-buffered popup slot at the
     *  end of the data region. [windowId:4][bufIndex:4][w:4][h:4][stride:4][x:4(f32)][y:4(f32)]
     *  [dipW:4(f32)][dipH:4(f32)] — w/h/stride are the device-px BGRA bitmap; x/y/dipW/dipH are
     *  the popup's rect in the WebView node's local (logical) space (bitmap stretched into it). */
    static final int POPUP_FRAME = 0x0601;
    /** [windowId:4] — the OSR popup closed; clear the overlay. */
    static final int POPUP_CLOSED = 0x0602;

    // Print preview overlay (0x0610–0x061F). The engine opened an off-screen
    // chrome://print WebContents for window.print()/Ctrl+P; it is composited as a
    // modal overlay over the initiator view (see docs/WEBVIEW_PRINT_PREVIEW.md).
    /** [windowId:4][previewHandle:4] — a print-preview overlay opened; drive
     *  capture of {@code previewHandle} and composite it modally over the view. */
    static final int PRINT_PREVIEW_OPENED = 0x0610;
    /** [windowId:4][previewHandle:4] — the preview closed; remove the overlay. */
    static final int PRINT_PREVIEW_CLOSED = 0x0611;
    /** A print-preview MODAL frame in the dedicated PREVIEW region (separate from
     *  POPUP_FRAME so the preview's own dropdowns can use the popup region). Same
     *  payload shape as POPUP_FRAME; x/y are 0 (Java centers it over the page). */
    static final int PREVIEW_FRAME = 0x0612;

    // Error / diagnostics (0x0420–0x042F)
    /** [windowId:4][errorCode:4(int32, negative net::Error)][urlLen:4][url:N][descLen:4][desc:N] */
    static final int LOAD_ERROR = 0x0420;
    /** [windowId:4][level:4][lineNumber:4][msgLen:4][msg:N][sourceIdLen:4][sourceId:N] */
    static final int CONSOLE_MESSAGE = 0x0421;
    /** [windowId:4][msgLen:4][utf8Message:N] */
    static final int IPC_ERROR = 0x0422;

    // DOM tree sync (0x0500–0x050F)
    // NOTE: every DOM event below is written through EventWriter::WriteEvent,
    // which prepends [windowId:4]. The jux_event_types.h comments document the
    // span *after* that prefix, so on the wire the node id is at offset 4.
    /** [windowId:4][nodeId:4][parentId:4][tagLen:1][tag:N][idLen:1][id:N][classLen:2][class:N] */
    static final int DOM_ELEMENT = 0x0500;
    /** [windowId:4][nodeId:4][parentId:4][textLen:2][text:N] */
    static final int DOM_TEXT = 0x0501;
    /** [windowId:4] — tree walk finished; arms getDocument()/DOCUMENT_AVAILABLE. */
    static final int DOM_TREE_READY = 0x0502;

    // DOM interaction (0x0510–0x052F) — a registered Blink listener fired.
    // Wire: [windowId:4][nodeId:4] + per-type tail (see jux_dom_client_impl.cc).
    static final int DOM_CLICK = 0x0510;
    static final int DOM_MOUSE_ENTER = 0x0511;
    static final int DOM_MOUSE_LEAVE = 0x0512;
    static final int DOM_FOCUS = 0x0513;
    static final int DOM_BLUR = 0x0514;
    static final int DOM_MOUSE_DOWN = 0x0515;
    static final int DOM_MOUSE_UP = 0x0516;
    static final int DOM_MOUSE_MOVE = 0x0517;
    static final int DOM_KEY_DOWN = 0x0518;
    static final int DOM_KEY_UP = 0x0519;
    static final int DOM_KEY_PRESS = 0x051A;
    static final int DOM_DBLCLICK = 0x051B;
    static final int DOM_CONTEXT_MENU = 0x051C;
    static final int DOM_MOUSE_OVER = 0x051D;
    static final int DOM_MOUSE_OUT = 0x051E;
    static final int DOM_FOCUS_IN = 0x051F;
    static final int DOM_FOCUS_OUT = 0x0520;
    static final int DOM_SCROLL = 0x0521;
    static final int DOM_INPUT = 0x0522;

    // DOM mutation (0x0530–0x053F) — non-Java DOM changes (JS/parser) on tracked nodes.
    /** [windowId:4][nodeId:4][nameLen:2][name:N][oldLen:2][old:N][newLen:2][new:N] */
    static final int MUTATION_ATTRIBUTE = 0x0530;
    /** [windowId:4][parentId:4][addedCount:4]{[id:4]}…[removedCount:4]{[id:4]}… */
    static final int MUTATION_CHILDREN = 0x0531;
    /** [windowId:4][nodeId:4][oldLen:2][old:N][newLen:2][new:N] */
    static final int MUTATION_TEXT = 0x0532;

    // JS dialogs (0x0430–0x043F). The engine has stashed Chromium's continuation
    // and suspended the page's JS until a DIALOG_RESPONSE command arrives.
    /** [windowId:4][dialogId:4][dialogType:4][msgLen:4][utf8Msg:N][defLen:4][utf8Default:N]
     *  dialogType: 0=alert, 1=confirm, 2=prompt, 3=beforeunload. */
    static final int DIALOG_REQUESTED = 0x0430;

    /** Engine needs a save location for a print-to-PDF. Java shows a JavaFX
     *  FileChooser (owned by the WebView's Stage) and answers with the
     *  SAVE_PDF_RESPONSE command. [windowId:4][requestId:4][nameLen:4][utf8Name:N] */
    static final int SAVE_PDF_REQUESTED = 0x0431;

    // Choosers (0x0440–0x044F).
    /** [windowId:4][chooserId:4][initialRgba:4][suggCount:4]{[rgba:4]}… */
    static final int COLOR_CHOOSER_OPEN = 0x0440;
    /** [windowId:4][popupId:4][flags:4(bit0=multiple)][selIndex:4][anchorX:4(f32)][anchorY:4(f32)]
     *  [anchorW:4(f32)][anchorH:4(f32)][pathLen:4][utf8TempFilePath:N] — items JSON in the temp file. */
    static final int SELECT_POPUP_OPEN = 0x0441;
    /** [windowId:4][chooserId:4][mode:4][initLen:4][utf8InitialName:N][filtLen:4][utf8MimeFilters:N] */
    static final int FILE_CHOOSER_REQUESTED = 0x0442;

    // Permissions (0x0450–0x045F).
    /** [windowId:4][permId:4][permType:4][originLen:4][utf8Origin:N] */
    static final int PERMISSION_REQUESTED = 0x0450;

    // Auth (0x0460–0x046F).
    /** [windowId:4][authId:4][scheme:4][isProxy:1][hostLen:4][utf8Host:N][realmLen:4][utf8Realm:N] */
    static final int AUTH_REQUESTED = 0x0460;

    // Downloads (0x0470–0x047F).
    /** [windowId:4][downloadId:4][totalBytes:8][urlLen:4][utf8Url:N][nameLen:4][utf8Name:N][mimeLen:4][utf8Mime:N] */
    static final int DOWNLOAD_REQUESTED = 0x0470;
    /** [windowId:4][downloadId:4][state:4][received:8][total:8] */
    static final int DOWNLOAD_PROGRESS = 0x0471;
    /** [windowId:4][downloadId:4][state:4][pathLen:4][utf8Path:N] */
    static final int DOWNLOAD_FINISHED = 0x0472;

    // Context menu / fullscreen / misc (0x0480–0x048F).
    /** [windowId:4][menuId:4][x:4(f32)][y:4(f32)][flags:4][linkLen:4][utf8Link:N][srcLen:4][utf8Src:N][selLen:4][utf8Selection:N]
     *  flags: bit0=editable, bit1=hasLink, bit2=hasImage, bit3=hasSelection. Fired by the
     *  browser on right-click; the WebView fires a JavaFX ContextMenuEvent with this
     *  context available via WebEngine.getContextMenuContext() (menuId unused here). */
    static final int CONTEXT_MENU_REQUESTED = 0x0480;
    /** [windowId:4][fsId:4][entering:1] */
    static final int FULLSCREEN_REQUESTED = 0x0481;
    /** [windowId:4][urlLen:4][utf8IconUrl:N] */
    static final int FAVICON_CHANGED = 0x0482;
    /** [windowId:4][textLen:4][utf8Text:N] */
    static final int TOOLTIP_CHANGED = 0x0483;

    // Network interception (0x0700–0x070F). Bodies ride a dedicated body region.
    /** [windowId:4][interceptId:4][resourceType:4][methodLen:2][method][urlLen:4][url][hdrBlobLen:4][hdrBlob] */
    static final int REQUEST_WILL_BE_SENT = 0x0700;
    /** [windowId:4][interceptId:4][status:4][mimeLen:2][mime][contentLen:8][hdrBlobLen:4][hdrBlob][flags:4] */
    static final int RESPONSE_RECEIVED = 0x0701;
    /** [windowId:4][interceptId:4][chunkSeq:4][offset:8][last:1][len:4][pathLen:4][utf8Path:N]
     *  — the chunk rides a temp file (path); the engine writes it, Java reads then
     *  deletes it. (Not the bodySlot/shm-region layout an older comment described.) */
    static final int RESPONSE_BODY_CHUNK = 0x0702;
    /** [windowId:4][interceptId:4][netError:4(int32)] — load resumed/failed; frees the Java exchange. */
    static final int INTERCEPT_COMPLETE = 0x0703;
}
