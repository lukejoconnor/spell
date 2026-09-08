import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Spell',
  description: 'A language for self-programmed execution',
  base: '/spell/',
  cleanUrls: true,
  lastUpdated: true,
  markdown: {
    theme: { light: 'github-light', dark: 'github-dark' }
  },
  themeConfig: {
    nav: [
      { text: 'Start', link: '/start' },
      { text: 'Language', link: '/language-overview' },
      { text: 'Communication', link: '/multi-agent' },
      { text: 'Capabilities & configuration', link: '/capabilities' },
      { text: 'API reference', link: '/api' },
      { text: 'Changelog', link: '/CHANGELOG' }
    ],
    sidebar: [
      {
        text: 'Start here',
        items: [
          { text: 'Overview', link: '/' },
          { text: 'Install and run', link: '/start' }
        ]
      },
      {
        text: 'Guide',
        items: [
          { text: 'Spell language overview', link: '/language-overview' },
          { text: 'Bounded results and paging', link: '/bounded-results' },
          { text: 'Multi-agent communication', link: '/multi-agent' },
          { text: 'Installable modules', link: '/installable-modules' },
          { text: 'Mailing lists', link: '/mailing-list' },
          { text: 'Capabilities and configuration', link: '/capabilities' }
        ]
      },
      {
        text: 'Reference',
        items: [
          { text: 'API and configuration', link: '/api' },
          { text: 'Error recovery', link: '/error-recovery' },
          { text: 'Changelog', link: '/CHANGELOG' }
        ]
      }
    ],
    search: { provider: 'local' },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/lukejoconnor/spell' }
    ],
    editLink: {
      pattern: 'https://github.com/lukejoconnor/spell/edit/main/docs/:path',
      text: 'Edit this page on GitHub'
    },
    outline: { level: [2, 3] },
    docFooter: { prev: 'Previous', next: 'Next' },
    lastUpdated: { text: 'Last updated' },
    footer: {
      message: 'Spell is a research prototype.',
      copyright: 'Released under the MIT License.'
    }
  }
})
