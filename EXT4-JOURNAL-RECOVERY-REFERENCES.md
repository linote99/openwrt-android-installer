# ext4 journal 修复设计依据

本功能故意比通用文件系统修复更受限。

- [e2fsck(8) 手册](https://man7.org/linux/man-pages/man8/e2fsck.8.html)说明 `-E journal_only` 在需要时只回放日志，不继续做其他检查或修复。因此应用只对已核实的目标分区调用 `e2fsck -p -E journal_only`，只接受退出码 0 或 1；随后另行运行完整只读 `e2fsck -f -n`。不会降级到 `-y`、格式化、扩容或其它修复模式。
- [Linux ext4 journal 文档](https://docs.kernel.org/filesystems/ext4/journal.html)说明默认 `data=ordered` 模式只把文件系统元数据放进 journal；`data=journal` 则会把文件数据和元数据一并写入 journal。[ext4(5) 手册](https://man7.org/linux/man-pages/man5/ext4.5.html)也说明 `data=journal` 会将全部数据提交到 journal。因为日志回放在该模式下可能写文件数据，应用会在超级块默认选项/选项、已安装启动命令行与 ramdisk、以及 OpenWrt `/etc/config/fstab` 中检查并拒绝该模式。
- Linux 内核的 [ext4 磁盘超级块定义](https://raw.githubusercontent.com/torvalds/linux/master/fs/ext4/ext4.h)定义 `s_default_mount_opts`、`EXT4_DEFM_JMODE` 掩码以及 journal inode/device 字段。应用要求 journal 在目标分区内部（`s_journal_inum` 非零、`s_journal_dev` 为零），拒绝外置 journal，以免涉及其它块设备。

这些资料说明工具选项的语义，但不能证明它在用户的实体设备上必然安全或成功。因此实现还会重新核对 MU300 UFS 身份、GPT 主备表、分区 GUID/几何、ext4 UUID/布局、安装包工具哈希及未挂载状态；任一条件不符即拒绝继续。
