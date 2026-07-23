const params = new URLSearchParams(window.location.search);
const message = document.querySelector('#loginMessage');
const password = document.querySelector('#password');
const passwordToggle = document.querySelector('#passwordToggle');

if (params.has('error')) {
  message.textContent = '账号或密码错误，请检查后重新登录。';
  message.hidden = false;
}

if (params.has('logout')) {
  message.textContent = '您已安全退出授权中心。';
  message.classList.add('success');
  message.hidden = false;
}

passwordToggle.addEventListener('click', () => {
  const visible = password.type === 'text';
  password.type = visible ? 'password' : 'text';
  passwordToggle.setAttribute('aria-label', visible ? '显示密码' : '隐藏密码');
});
