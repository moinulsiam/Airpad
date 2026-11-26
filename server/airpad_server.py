"""AirPad Server (Windows). Receives phone commands over WiFi (UDP) and moves the mouse / types keys."""
import ctypes
import ctypes.wintypes as wt
import os
import random
import socket
import threading
import time
import tkinter as tk
from tkinter import filedialog

PORT = 50505
FILE_PORT = 50506
RECV_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "AirPad")
QUEUE = []          # files waiting to be sent to the phone
QUEUE_LOCK = threading.Lock()
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
STATE = {"pin": "", "last": None, "error": "", "log": []}
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
            n = len(QUEUE)
            sock.sendto(b"OK" if n == 0 else ("OK|%d" % n).encode(), addr)
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


# ------------------------------------------------------------------ file transfer (TCP)
def log(msg):
    STATE["log"].append(msg)
    del STATE["log"][:-5]


def read_line(conn, limit=4096):
    buf = b""
    while len(buf) < limit:
        c = conn.recv(1)
        if not c or c == b"\n":
            break
        buf += c
    return buf.decode("utf-8", "ignore").strip()


def safe_name(name):
    name = os.path.basename(name.replace("\\", "/"))
    for ch in '<>:"/\\|?*':
        name = name.replace(ch, "_")
    return name.strip() or "file"


def unique_path(folder, name):
    os.makedirs(folder, exist_ok=True)
    base, ext = os.path.splitext(name)
    path = os.path.join(folder, name)
    i = 1
    while os.path.exists(path):
        path = os.path.join(folder, "%s (%d)%s" % (base, i, ext))
        i += 1
    return path


def handle_conn(conn):
    path = None
    done = False
    try:
        conn.settimeout(30)
        parts = read_line(conn).split("|", 3)
        if len(parts) < 2 or parts[1] != STATE["pin"]:
            conn.sendall(b"ERR\n")
            return
        kind = parts[0]
        if kind == "U" and len(parts) == 4:
            size = int(parts[2])
            path = unique_path(RECV_DIR, safe_name(parts[3]))
            remaining = size
            with open(path, "wb") as f:
                while remaining > 0:
                    chunk = conn.recv(min(65536, remaining))
                    if not chunk:
                        raise IOError("connection closed")
                    f.write(chunk)
                    remaining -= len(chunk)
            done = True
            conn.sendall(b"OK\n")
            log("Received: " + os.path.basename(path))
        elif kind == "G":
            with QUEUE_LOCK:
                item = QUEUE[0] if QUEUE else None
            if item is None or not os.path.exists(item):
                if item is not None:
                    with QUEUE_LOCK:
                        QUEUE.remove(item)
                conn.sendall(b"N\n")
                return
            size = os.path.getsize(item)
            name = os.path.basename(item).replace("\n", " ")
            conn.sendall(("F|%d|%s\n" % (size, name)).encode("utf-8"))
            with open(item, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    conn.sendall(chunk)
            if read_line(conn) == "A":
                with QUEUE_LOCK:
                    if item in QUEUE:
                        QUEUE.remove(item)
                log("Sent to phone: " + name)
    except Exception:
        pass
    finally:
        if path and not done:
            try:
                os.remove(path)
            except OSError:
                pass
        conn.close()


def file_server():
    try:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", FILE_PORT))
        srv.listen(5)
    except OSError as e:
        STATE["error"] = "File port %d busy: %s" % (FILE_PORT, e)
        return
    while True:
        try:
            conn, _ = srv.accept()
        except OSError:
            continue
        threading.Thread(target=handle_conn, args=(conn,), daemon=True).start()


# ------------------------------------------------------------------ window
def main():
    load_pin()
    threading.Thread(target=serve, daemon=True).start()
    threading.Thread(target=file_server, daemon=True).start()

    root = tk.Tk()
    root.title("AirPad Server")
    root.geometry("380x420")
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

    tk.Button(root, text="New PIN", command=change_pin).pack(pady=4)

    def send_to_phone():
        files = filedialog.askopenfilenames(title="Choose files to send to the phone")
        with QUEUE_LOCK:
            QUEUE.extend(files)

    def open_folder():
        os.makedirs(RECV_DIR, exist_ok=True)
        os.startfile(RECV_DIR)

    tk.Button(root, text="Send file to phone", command=send_to_phone,
              font=("Segoe UI", 11, "bold")).pack(pady=4, ipadx=10)
    tk.Button(root, text="Open received files folder", command=open_folder).pack(pady=2)
    q_lbl = tk.Label(root, font=("Segoe UI", 10))
    q_lbl.pack(pady=(8, 0))
    log_lbl = tk.Label(root, font=("Segoe UI", 9), fg="#555", justify="left")
    log_lbl.pack()

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
        q_lbl.config(text="Waiting to send to phone: %d file(s)" % len(QUEUE))
        log_lbl.config(text="\n".join(STATE["log"][-4:]))
        root.after(1000, refresh)

    refresh()
    root.mainloop()


if __name__ == "__main__":
    main()
