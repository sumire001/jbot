const ThemeManager = {
    THEME_KEY: 'jone_theme',
    SIDEBAR_COLLAPSED: 'jone_sidebar_collapsed',

    themes: {
        system: 'system',
        light: 'light',
        dark: 'dark'
    },

    init() {
        this.applyTheme(this.getTheme());
        this.applySidebarState(this.isSidebarCollapsed());
        this.listenSystemTheme();
    },

    getTheme() {
        return localStorage.getItem(this.THEME_KEY) || 'system';
    },

    setTheme(theme) {
        localStorage.setItem(this.THEME_KEY, theme);
        this.applyTheme(theme);
    },

    applyTheme(theme) {
        const root = document.documentElement;
        let isDark = false;

        if (theme === 'system') {
            isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        } else {
            isDark = theme === 'dark';
        }

        if (isDark) {
            root.setAttribute('data-theme', 'dark');
        } else {
            root.setAttribute('data-theme', 'light');
        }

        this.updateThemeSelector(theme);
    },

    listenSystemTheme() {
        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
            if (this.getTheme() === 'system') {
                this.applyTheme('system');
            }
        });
    },

    updateThemeSelector(theme) {
        const selector = document.querySelector('.theme-selector');
        if (selector) {
            selector.value = theme;
        }
    },

    isSidebarCollapsed() {
        return localStorage.getItem(this.SIDEBAR_COLLAPSED) === 'true';
    },

    toggleSidebarCollapse() {
        const sidebar = document.getElementById('sidebar');
        if (!sidebar) return;

        const collapsed = !this.isSidebarCollapsed();
        localStorage.setItem(this.SIDEBAR_COLLAPSED, collapsed);
        this.applySidebarState(collapsed);
    },

    applySidebarState(collapsed) {
        const sidebar = document.getElementById('sidebar');
        if (!sidebar) return;

        if (collapsed) {
            sidebar.classList.add('collapsed');
        } else {
            sidebar.classList.remove('collapsed');
        }
        // 同步到 body，供"收起后的唤出按钮"显隐与内容区留白使用
        document.body.classList.toggle('sidebar-collapsed', collapsed);

        this.updateCollapseIcon(collapsed);
    },

    updateCollapseIcon(collapsed) {
        // 折叠图标由 CSS 根据 .sidebar.collapsed 状态切换 Lucide 图标显隐，无需 JS 设置文字
    },

    toggleSidebar() {
        const sidebar = document.getElementById('sidebar');
        const overlay = document.getElementById('sidebar-overlay');
        if (sidebar && overlay) {
            sidebar.classList.toggle('open');
            overlay.classList.toggle('show');
        }
    },

    closeSidebar() {
        const sidebar = document.getElementById('sidebar');
        const overlay = document.getElementById('sidebar-overlay');
        if (sidebar && overlay) {
            sidebar.classList.remove('open');
            overlay.classList.remove('show');
        }
    }
};

document.addEventListener('DOMContentLoaded', () => {
    ThemeManager.init();
    // 将 <i data-lucide="..."> 渲染为精致 SVG 矢量图标（若有 Lucide 库）
    if (window.lucide) {
        lucide.createIcons();
    }
});

window.ThemeManager = ThemeManager;
