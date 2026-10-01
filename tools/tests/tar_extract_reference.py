#!/usr/bin/env python3
"""
Ubuntu Base tarball 解压参考实现 + 实测断言（用于验证 app 内 Kotlin 版 ZstdTarExtractor 的解析规则）。

为什么需要它：
  App 内的 ZstdTarExtractor 是手写的 tar 解析器（Android 没有 tarfile 这类库）。
  手写解析器极易在「PAX 扩展头 / GNU 长文件名 / 硬链接」上出错，而这些用例在
  Ubuntu Base 官方 tarball 里真实存在。本脚本用 Python 标准库 tarfile 做参考实现，
  并断言「跳过 PAX（只取 ustar 字段）」在当前 tarball 上是否安全。

用法：
  curl -LO https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz
  python3 tools/tests/tar_extract_reference.py ubuntu-base-24.04.5-base-arm64.tar.gz

退出码：0 = 全部断言通过；1 = 有断言失败（说明 Kotlin 侧的解析规则需要调整）。
"""
import sys, tarfile, collections, posixpath

def main(path: str) -> int:
    fail = []
    with tarfile.open(path, "r:*") as tf:
        members = tf.getmembers()
        files = [m for m in members if m.isfile()]
        dirs  = [m for m in members if m.isdir()]
        links = [m for m in members if m.issym()]
        hards = [m for m in members if m.islnk()]

        print(f"tarball      : {path}")
        print(f"条目总数     : {len(members)}")
        print(f"  常规文件   : {len(files)}")
        print(f"  目录       : {len(dirs)}")
        print(f"  符号链接   : {len(links)}")
        print(f"  硬链接     : {len(hards)}")
        print(f"  format     : {tf.format}")

        # 断言 1：必须只含常规类型，不应出现设备节点/fifo（PRoot 下无法创建）
        odd = [m.name for m in members if m.ischr() or m.isblk() or m.isfifo()]
        if odd:
            fail.append(f"含设备/fifo 条目 {len(odd)} 个：{odd[:3]}")
        else:
            print("断言1 无非常规类型条目            : PASS")

        # 断言 2：PAX 头承载了哪些键（决定「跳过 PAX」是否安全）
        keys = collections.Counter()
        for m in members:
            for k in (m.pax_headers or {}):
                keys[k] += 1
        print(f"PAX 键统计   : {dict(keys)}")
        risky = {k: v for k, v in keys.items() if k in ("path", "linkpath", "size")}
        if risky:
            print(f"断言2 PAX 未承载 path/linkpath/size: FAIL（{risky}）→ Kotlin 侧必须解析 PAX")
            fail.append(f"PAX 承载关键字段：{risky}")
        else:
            print("断言2 PAX 仅承载时间戳            : PASS（跳过 PAX 不影响数据）")

        # 断言 3：路径长度是否超 ustar 的 100 字节 name 字段
        longest = max((len(m.name) for m in members), default=0)
        over100 = [m.name for m in members if len(m.name) > 100]
        print(f"最长路径     : {longest} 字节；>100 字节的条目: {len(over100)}")
        if over100:
            fail.append(f"存在 >100 字节路径 {len(over100)} 个，必须靠 PAX/L 头承载：{over100[:2]}")
        else:
            print("断言3 无超长路径                  : PASS（但 Kotlin 仍应支持 PAX.path）")

        # 断言 4：硬链接目标必须存在于归档内；并报告其先后顺序
        #（顺序异常不会让我们的实现失败——它有「目标不存在则跳过/复制」降级，但值得记录）
        names = {m.name for m in members}
        order = {m.name: i for i, m in enumerate(members)}
        bad_hard, late_hard = [], []
        for m in hards:
            tgt = m.linkname.lstrip("/")
            if tgt not in names:
                bad_hard.append(f"{m.name} -> {tgt}（目标不在归档内）")
            elif order.get(tgt, 1 << 30) > order[m.name]:
                late_hard.append(f"{m.name} -> {tgt}（目标在链接之后出现，需降级处理）")
        if bad_hard:
            print("断言4 硬链接目标存在于归档内      : FAIL")
            for b in bad_hard:
                print(f"   - {b}")
            fail.append("硬链接目标不在归档内 → Kotlin 必须容忍「目标不存在」而不是抛异常")
        else:
            extra = f"（其中 {len(late_hard)} 个目标后置，走降级分支）" if late_hard else "（目标均前置，可正常解析）"
            print(f"断言4 硬链接目标存在于归档内      : PASS {extra}")

    print()
    if fail:
        print("结果：FAIL")
        for f in fail:
            print("  -", f)
        return 1
    print("结果：PASS —— 与 app 内 ZstdTarExtractor 的解析规则一致")
    return 0

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
