/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './public/index.html',
    './src/**/*.{vue,js}'
  ],
  corePlugins: {
    preflight: false
  },
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#eef6ff',
          100: '#d9ebff',
          500: '#2684ff',
          600: '#1769e0',
          700: '#0f55bd'
        },
        ink: {
          900: '#111827',
          700: '#334155',
          500: '#64748b'
        }
      },
      boxShadow: {
        panel: '0 18px 45px rgba(15, 23, 42, 0.07)',
        focus: '0 0 0 3px rgba(38, 132, 255, 0.16)'
      }
    }
  },
  plugins: []
};
