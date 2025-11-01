{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
  };

  outputs = {
    self,
    nixpkgs,
  }: let
    supportedSystems = ["x86_64-linux" "aarch64-linux"];
    pkgsBySystem = nixpkgs.lib.getAttrs supportedSystems nixpkgs.legacyPackages;
    forAllPkgs = fn: nixpkgs.lib.mapAttrs (_: pkgs: (fn pkgs)) pkgsBySystem;
  in {
    formatter = forAllPkgs (pkgs: pkgs.alejandra);
    devShells = forAllPkgs (pkgs: {
      default = pkgs.mkShell {
        buildInputs = [
          (pkgs.python3.withPackages (ps: [ps.pygit2]))
          pkgs.git
        ];
      };
    });
  };
}
