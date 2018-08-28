const { Input, Button } = window['choerodon-ui'];

class LoginButton extends window.React.Component{
    state = {
        loading: false,
    };

    constructor(props) {
        super(props);
    }
    handleButtonClickTest = (e) => {
        this.setState({ loading: true })
        const promise = new Promise((resolve, reject) => {
            setTimeout(resolve, 2000)
        });

        promise.then(() => {
            this.setState({ loading: false })
        })
        $("#usernameIsNullMsg").css('display', 'none');
        $("#passwordIsNullMsg").css('display', 'none');
        $("#usernameOrPasswordNotFoundMsg").html("");
        var username = $.trim($("#username").val());
        var password = $.trim($("#password").val());
        if (username == '') {
            $("#usernameIsNullMsg").css('display', 'block');
            return;
        }
        if (password == '') {
            $("#passwordIsNullMsg").css('display', 'block');
            return;
        }
        $("#md5_password").val(this.encode(password));
        $('.login-form').submit();
    };

   encode = (password) => {
        var output = "";
        var chr1, chr2, chr3 = "";
        var enc1, enc2, enc3, enc4 = "";
        var i = 0;
        do {
            chr1 = password.charCodeAt(i++);
            chr2 = password.charCodeAt(i++);
            chr3 = password.charCodeAt(i++);
            enc1 = chr1 >> 2;
            enc2 = ((chr1 & 3) << 4) | (chr2 >> 4);
            enc3 = ((chr2 & 15) << 2) | (chr3 >> 6);
            enc4 = chr3 & 63;
            if (isNaN(chr2)) {
                enc3 = enc4 = 64;
            } else if (isNaN(chr3)) {
                enc4 = 64;
            }
            output = output + keyStr.charAt(enc1) + keyStr.charAt(enc2)
                + keyStr.charAt(enc3) + keyStr.charAt(enc4);
            chr1 = chr2 = chr3 = "";
            enc1 = enc2 = enc3 = enc4 = "";
        } while (i < password.length);
        return output;
    }


    render() {
        return (
            <Button className="btn" onClick={this.handleButtonClickTest} loading={this.state.loading} htmlStyle="padding-top:4px"><span>{this.state.loading ? '登陆中' : '登陆'}</span></Button>
        )
    }
}

class UsernameInupt extends window.React.Component{
    state = {
        currentUsername: '',
    };

    componentDidMount() {
        this.setState({
            currentUsername: this.getUrlParams('username'),
        })
    }
    getUrlParams = (name) => {
        var reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i"); //定义正则表达式
        var r = window.location.search.substr(1).match(reg);
        if (r != null) return unescape(r[2]);
        return null;
    }
    onValueChange = (e) => {
        this.setState({
            currentUsername: e.target.value
        })
    }

    render() {
        return (
            <Input autoFocus autoComplete="off" label="登陆账号" name="username" id="username" onChange={e => this.onValueChange(e)} placeholder="登录名/邮箱" value={this.state.currentUsername}/>
        )
    }
}

/**
 * 渲染账号和密码的输入框
 */
ReactDOM.render(
    <UsernameInupt />,
    document.getElementById('usernameInupt'));
ReactDOM.render(
<div>
<Input type="password" autoComplete="off" label="密码" id="password" placeholder="请输入密码"/>
    </div>,
    document.getElementById('passwordInupt'));
ReactDOM.render(
<LoginButton/>,
    document.getElementById('loginButton'));