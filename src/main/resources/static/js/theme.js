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

        this.updateCollapseIcon(collapsed);
    },

    updateCollapseIcon(collapsed) {
        const btn = document.getElementById('collapse-btn');
        if (btn) {
            btn.textContent = collapsed ? '→' : '←';
            btn.title = collapsed ? '展开侧边栏' : '收起侧边栏';
        }
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
});

window.ThemeManager = ThemeManager;
