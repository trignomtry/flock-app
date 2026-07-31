# Product Design Specification: Spaces, Topic Channels, & Quiet Sub-Threads

## Vision & Philosophy
Group chats should feel like hanging out in a house with different rooms—not an endless megathread where everyone has to shout over each other. 

This feature solves group chat bloat, message drowning, and notification fatigue by introducing a two-tier organizational hierarchy (**Space → Channels**) with **Opt-In Sub-Threads** and **Zero-Noise Controls**. Joining a side topic should feel effortless, switching between topics should feel instantaneous, and muting or leaving a noisy thread must carry zero social awkwardness.

---

## 1. Core Layout & Navigation

### The Dual-Tier Structure
Every group chat on the platform is a **Space** (e.g., "Northrup Family"). Within a Space, conversations are divided into **Topic Channels** (e.g., `#general`, `#germany-trip`, `#wordle`).

*   **Home Base (`#general`):** Every Space has a default `#general` channel for important group updates, announcements, and primary chatter.
*   **Topic Pill Bar:** Positioned directly below the top app bar, render a horizontally scrolling bar of pill chips representing available channels:
    ```text
    ┌────────────────────────────────────────────────────────┐
    │  <  Northrup Family                    [+] [Settings] │
    │  [ # general ]  [ # germany-trip ]  [ # wordle ]       │
    ├────────────────────────────────────────────────────────┤
    │                                                        │
    │  Dad: Did everyone book their flights?                 │
    │                                                        │
    └────────────────────────────────────────────────────────┘
    ```

### User Experience & Transitions
*   **1-Tap Switching:** Tapping a channel pill chip instantly switches the message view to that channel without taking the user to a new screen.
*   **Hardware-Accelerated Motion:** Channel switching must feel completely fluid (120Hz capable). The current conversation smoothly slides over or crossfades into the new topic stream.
*   **No Blank Loading States:** Cached local messages must render immediately upon tapping a pill chip. Do not show full-screen spinners or clear the screen when switching between channels.

---

## 2. Topic Creation & Opt-In Flow

### Creating a Topic Channel
*   Users tap the `[+]` action next to the channel pill bar to open a clean `Create Topic Channel` bottom sheet.
*   The sheet prompts for a **Channel Name** (e.g., "Germany Trip") and an optional **Emoji Icon**.
*   **Privacy & Broadcast Setting:** A simple binary choice:
    *   🔘 **Public Topic (Notify Everyone):** Best for high-importance planning. Sends a single non-intrusive invite card to `#general`.
    *   🔘 **Quiet / Opt-In Topic:** Best for hobbies, daily games, or niche banter. Spawns silently without alerting the whole group.

### System Invitations
When a new topic channel is created, an inline, non-blocking invitation card appears inside `#general`:

> **Dad created a new topic:** `#wordle`  
> *[ Join Topic ]* &nbsp;&nbsp;&nbsp;&nbsp; *[ Ignore ]*

---

## 3. The "Zero-Noise" Notification Invariant

This is the core product rule that prevents group chat fragmentation and stops users from leaving groups:

1. **No Badge Inflation:** Activity in a channel that the user has not explicitly joined (or has muted) **must never increment the app's global unread badge count**.
2. **No Unwanted Vibrations/Pushes:** Muted or unjoined sub-threads must **never trigger OS-level push notifications** (APNs/FCM) on the user's device.
3. **Ghost Exiting:** If a user chooses to mute or leave a sub-thread, **no public system message** (e.g., *"Trig left the chat"*) will ever be broadcast to the group. The user quietly slips out.

---

## 4. Smart Feed Collapsing (In-Line Tagging)

For users who prefer to post repetitive daily updates (e.g., Wordle scores, sports scores, or links) directly in the main `#general` channel:

### Collapsible Accordion Cards
*   **Pattern Recognition:** Detect repetitive structured posts or tagged content (e.g., `[Wordle 1,124 3/6]`).
*   **Auto-Grouping:** When multiple matching posts occur sequentially, automatically collapse them into a single-line accordion view:
    > 🧩 **12 Wordle scores posted today** · *[ Tap to expand ]*
*   **The Experience:** Tapping the summary expands the posts inline. The primary chat timeline stays clean, readable, and focused on meaningful conversation.

---

## 5. Summary UX Checklist for Implementation
- [ ] **Top Pill Bar:** Swipeable horizontal list of channel pills anchored under the Space header.
- [ ] **Opt-In Sub-Threads:** Quiet creation toggle for hobbies and side topics.
- [ ] **Zero-Noise Enforcement:** Muted or unjoined channels generate zero push notifications and zero unread badge counts.
- [ ] **Ghost Leaving:** Silent user opt-out without "user left the chat" banners.
- [ ] **Inline Accordions:** Automatically collapse repetitive or tagged posts in main channels.
