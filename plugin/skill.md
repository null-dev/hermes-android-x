# Android control

Control a paired Android phone over your local network.

## Setup
1. Install the Hermes Bridge app on the phone and enable its accessibility service.
2. Tap **Start bridge**. The app shows a URL (`http://<phone-ip>:8765`) and a token.
3. Set `ANDROID_BRIDGE_URL` and `ANDROID_BRIDGE_TOKEN` in the agent environment.
4. Call `android_ping` to confirm connectivity.

The phone must be on the same LAN or VPN as the agent.

## Tools (this build)
- `android_ping` — connectivity + device info
- `android_read_screen` — accessibility tree (each node has an `id` usable by `android_tap`)
- `android_tap` — tap by `(x, y)` or `node_id`
- `android_type` — type into the focused field
