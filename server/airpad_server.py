"""AirPad Server (Windows). Receives phone commands over WiFi (UDP) and moves the mouse / types keys."""
import ctypes
import ctypes.wintypes as wt
import os
import random
import socket
import threading
import time
import tkinter as tk

PORT = 50505
ULONG_PTR = ctypes.c_size_t
user32 = ctypes.windll.user32


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wt.DWORD), ("u", _U)]


user32.SendInput.argtypes = (wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wt.UINT

KEY_EXT, KEY_UP, KEY_UNI = 0x0001, 0x0002, 0x0004
EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5C}
BTN_DOWN = {1: 0x0002, 2: 0x0008, 4: 0x0020}
BTN_UP = {1: 0x0004, 2: 0x0010, 4: 0x0040}


def _send(*inputs):
    n = len(inputs)
    if n == 0:
        return
    arr = (INPUT * n)(*inputs)
    user32.SendInput(n, arr, ctypes.sizeof(INPUT))


def _mouse(flags, dx=0, dy=0, data=0):
    i = INPUT(type=0)
    i.u.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
    return i


def _key(vk=0, scan=0, flags=0):
    i = INPUT(type=1)
    i.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
    return i


def press_key(vk, mods):
    mod_vks = []
    if mods & 1:
        mod_vks.append(0x11)  # Ctrl
    if mods & 2:
        mod_vks.append(0x10)  # Shift
    if mods & 4:
        mod_vks.append(0x12)  # Alt
    if mods & 8:
        mod_vks.append(0x5B)  # Win
    seq = []
    for m in mod_vks:
        seq.append(_key(m, flags=KEY_EXT if m == 0x5B else 0))
    ext = KEY_EXT if vk in EXTENDED else 0
    seq.append(_key(vk, flags=ext))
    seq.append(_key(vk, flags=ext | KEY_UP))
    for m in reversed(mod_vks):
        seq.append(_key(m, flags=(KEY_EXT if m == 0x5B else 0) | KEY_UP))
    _send(*seq)


def type_text(s):
    seq = []
    for ch in s:
        if ch == "\n":
            seq += [_key(0x0D), _key(0x0D, flags=KEY_UP)]
            continue
        data = ch.encode("utf-16-le")
        for i in range(0, len(data), 2):
            unit = data[i] | (data[i + 1] << 8)
            seq.append(_key(0, unit, KEY_UNI))
            seq.append(_key(0, unit, KEY_UNI | KEY_UP))
    _send(*seq)


# ------------------------------------------------------------------ state / pin
STATE = {"pin": "", "last": None, "error": ""}
PIN_FILE = os.path.join(os.environ.get("APPDATA", "."), "AirPad", "pin.txt")


def new_pin():
    pin = "%04d" % random.randint(0, 9999)
    try:
        os.makedirs(os.path.dirname(PIN_FILE), exist_ok=True)
        with open(PIN_FILE, "w") as f:
            f.write(pin)
    except OSError:
        pass
    STATE["pin"] = pin
    return pin


def load_pin():
    try:
        with open(PIN_FILE) as f:
            p = f.read().strip()
        if p.isdigit():
            STATE["pin"] = p
            return
    except OSError:
        pass
    new_pin()


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "unknown"


# ------------------------------------------------------------------ network loop
def handle(data, addr, sock):
    msg = data.decode("utf-8", "ignore")
    if msg == "AIRPAD?":
        sock.sendto(("AIRPAD!|" + socket.gethostname()).encode(), addr)
        return
    parts = msg.split("|", 2)
    if len(parts) < 2:
        return
    pin, cmd = parts[0], parts[1]
    arg = parts[2] if len(parts) > 2 else ""
    if pin != STATE["pin"]:
        sock.sendto(b"BADPIN", addr)
        return
    STATE["last"] = (addr[0], time.time())
    try:
        if cmd == "P":
            sock.sendto(b"OK", addr)
        elif cmd == "M":
            dx, dy = (int(v) for v in arg.split(","))
            _send(_mouse(0x0001, dx, dy))
        elif cmd == "B":
            mask, down = (int(v) for v in arg.split(","))
            flag = (BTN_DOWN if down else BTN_UP).get(mask)
            if flag:
                _send(_mouse(flag))
        elif cmd == "C":
            mask = int(arg)
            if mask in BTN_DOWN:
                _send(_mouse(BTN_DOWN[mask]), _mouse(BTN_UP[mask]))
        elif cmd == "W":
            _send(_mouse(0x0800, data=int(arg) * 120))
        elif cmd == "K":
            vk, mods = (int(v) for v in arg.split(","))
            press_key(vk, mods)
        elif cmd == "T":
            type_text(arg)
    except Exception:
        pass


def serve():
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", PORT))
    except OSError as e:
        STATE["error"] = "Port %d busy: %s" % (PORT, e)
        return
    while True:
        try:
            data, addr = sock.recvfrom(4096)
        except OSError:
            continue
        handle(data, addr, sock)


# ------------------------------------------------------------------ window
def main():
    load_pin()
    threading.Thread(target=serve, daemon=True).start()

    root = tk.Tk()
    root.title("AirPad Server")
    root.geometry("360x240")
    root.resizable(False, False)

    tk.Label(root, text="AirPad Server", font=("Segoe UI", 16, "bold")).pack(pady=(12, 2))
    ip_lbl = tk.Label(root, font=("Segoe UI", 11))
    ip_lbl.pack()
    pin_lbl = tk.Label(root, font=("Segoe UI", 26, "bold"), fg="#1565C0")
    pin_lbl.pack(pady=4)
    st_lbl = tk.Label(root, font=("Segoe UI", 11))
    st_lbl.pack(pady=4)
    tk.Label(root, text="Enter this PIN in the phone app.\nKeep this window open (minimize it).",
             font=("Segoe UI", 9), fg="#555").pack()

    def change_pin():
        new_pin()

    tk.Button(root, text="New PIN", command=change_pin).pack(pady=8)

    def refresh():
        ip_lbl.config(text="PC IP: " + local_ip())
        pin_lbl.config(text="PIN: " + STATE["pin"])
        last = STATE["last"]
        if STATE["error"]:
            st_lbl.config(text=STATE["error"], fg="#B71C1C")
        elif last and time.time() - last[1] < 6:
            st_lbl.config(text="Phone connected (" + last[0] + ")", fg="#2E7D32")
        else:
            st_lbl.config(text="Waiting for phone...", fg="#555")
        root.after(1000, refresh)

    refresh()
    root.mainloop()


if __name__ == "__main__":
    main()
