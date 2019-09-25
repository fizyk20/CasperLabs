#![no_std]

#[macro_use]
extern crate alloc;

use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;

extern crate contract_ffi;

use contract_ffi::contract_api::pointers::TURef;
use contract_ffi::contract_api::*;
use contract_ffi::key::Key;
use contract_ffi::uref::URef;


enum Error {
    MissingTURef = 1,
}

fn get_list_key(name: &str) -> TURef<Vec<String>> {
    get_uref(name).unwrap().to_turef().unwrap()
}

fn update_list(name: String) {
    let list_key = get_list_key("list");
    let mut list = match read(list_key.clone()) {
        Ok(Some(list)) => list,
        Ok(None) => revert(Error::ValueNotFound.into()),
        Err(_) => revert(Error::Read.into()),
    };
    list.push(name);
    write(list_key, list);
}

fn sub(name: String) -> Option<TURef<Vec<String>>> {
    if has_uref(&name) {
        let init_message = vec![String::from("Hello again!")];
        let new_key = new_turef(init_message);
        Some(new_key) //already subscribed
    } else {
        let init_message = vec![String::from("Welcome!")];
        let new_key = new_turef(init_message);
        add_uref(&name, &new_key.clone().into());
        update_list(name);
        Some(new_key)
    }
}

fn publish(msg: String) {
    let curr_list = match read(get_list_key("list")) {
        Ok(Some(list)) => list,
        Ok(None) => revert(Error::ValueNotFound.into()),
        Err(_) => revert(Error::Read.into()),
    };
    for name in curr_list.iter() {
        let uref = get_list_key(name);
        let mut messages = match read(uref.clone()) {
            Ok(Some(messages)) => messages,
            Ok(None) => revert(Error::ValueNotFound.into()),
            Err(_) => revert(Error::Read.into()),
        };
        messages.push(msg.clone());
        write(uref, messages);
    }
}

#[no_mangle]
pub extern "C" fn mailing_list_ext() {
    let method_name: String = get_arg(0).unwrap().unwrap();
    match method_name.as_str() {
        "sub" => match sub(get_arg(1).unwrap().unwrap()) {
            Some(turef) => {
                let extra_uref = URef::new(turef.addr(), turef.access_rights());
                ret(extra_uref);
            }
            _ => revert(Error::MissingTURef as u32),
        },
        //Note that this is totally insecure. In reality
        //the pub method would be only available under an
        //unforgable reference because otherwise anyone could
        //spam the mailing list.
        "pub" => {
            publish(get_arg(1).unwrap().unwrap());
        }
        _ => panic!("Unknown method name!"),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let init_list: Vec<String> = Vec::new();
    let list_key = new_turef(init_list);

    //create map of references for stored contract
    let mut mailing_list_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from("list");
    mailing_list_urefs.insert(key_name, list_key.into());

    let pointer = store_function("mailing_list_ext", mailing_list_urefs);
    add_uref("mailing", &pointer.into())
}
